package com.autotuning.backend.hw;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Porta em Java de {@code monitoring/collector.py} (repositorio Pipeline,
 * Python) — le os mesmos arquivos de {@code /proc} e {@code /sys} que o
 * psutil/hwmon liam la, sem depender de shell-out pro Python. Motivo:
 * {@code /api/metrics} e o endpoint de maior frequencia do backend (poll de
 * 1s por navegador conectado) — spawnar um processo Python a cada segundo
 * seria uma dependencia viva desnecessaria e cara.
 *
 * <p>Descoberta de sensores roda uma unica vez no startup ({@link PostConstruct}),
 * espelhando a auto-descoberta que o modulo Python faz ao ser importado.
 */
@Service
public class HardwareInfoService {

    private static final Path HWMON_ROOT = Path.of("/sys/class/hwmon");
    private static final Path RAPL_ENERGY = Path.of("/sys/class/powercap/intel-rapl:0/energy_uj");
    private static final Path RAPL_MAX = Path.of("/sys/class/powercap/intel-rapl:0/max_energy_range_uj");

    // ── Descoberto uma vez no startup ────────────────────────────────────
    private Path cpuTctl;
    private String cpuSensorName = "k10temp";
    private List<Path> cpuCores = List.of();
    private List<Path> nvmeTemps = List.of();
    private Path gpuEdge, gpuJunction, gpuMem;
    private List<Path> ramTemps = List.of();
    private Path acpitzTemp;
    private Path wifiTemp;
    private boolean raplReadable;
    private Long raplMaxUj;
    private List<String> wholeDiskNames = List.of();

    // ── Estado para calculo de delta (CPU% e disco) ──────────────────────
    private final Object cpuLock = new Object();
    private long prevCpuTotal = -1, prevCpuIdle = -1;
    private final Object diskLock = new Object();
    private long prevReadBytes = -1, prevWriteBytes = -1, prevDiskNanos = -1;

    @PostConstruct
    void discoverSensors() {
        cpuTctl = findHwmonTemp("k10temp", "Tctl");
        if (cpuTctl != null) {
            cpuSensorName = "k10temp";
        } else {
            cpuTctl = findHwmonTemp("coretemp", "Package id 0");
            cpuSensorName = cpuTctl != null ? "coretemp" : "k10temp";
        }
        cpuCores = discoverCpuCores();
        nvmeTemps = findAllHwmon("nvme", null);
        gpuEdge = findHwmonTemp("amdgpu", "edge");
        gpuJunction = findHwmonTemp("amdgpu", "junction");
        gpuMem = findHwmonTemp("amdgpu", "mem");
        ramTemps = findAllHwmon("spd5118", null);
        acpitzTemp = findHwmonTemp("acpitz", null);
        wifiTemp = Optional.ofNullable(findHwmonTemp("iwlwifi_1", null))
                .orElseGet(() -> findHwmonTemp("iwlwifi", null));

        try {
            raplReadable = Files.exists(RAPL_ENERGY) && Files.isReadable(RAPL_ENERGY);
            raplMaxUj = (raplReadable && Files.exists(RAPL_MAX))
                    ? Long.parseLong(readTrim(RAPL_MAX)) : null;
        } catch (Exception e) {
            raplReadable = false;
            raplMaxUj = null;
        }

        wholeDiskNames = discoverWholeDisks();

        // "Aquece" os contadores para que a primeira leitura real seja valida
        // (mesmo motivo do psutil.cpu_percent(interval=None) descartado no Python).
        readCpuDelta();
        readDiskDelta();
    }

    // ── Descoberta de sensores hwmon (porta de _find_hwmon_temp / _find_all_hwmon) ──

    private Path findHwmonTemp(String driverName, String label) {
        List<Path> found = findAllHwmon(driverName, label);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<Path> findAllHwmon(String driverName, String label) {
        List<Path> results = new ArrayList<>();
        if (!Files.isDirectory(HWMON_ROOT)) {
            return results;
        }
        try (Stream<Path> devs = Files.list(HWMON_ROOT)) {
            List<Path> sorted = devs.sorted().toList();
            for (Path dev : sorted) {
                Path nameFile = dev.resolve("name");
                if (!Files.exists(nameFile) || !readTrim(nameFile).equals(driverName)) {
                    continue;
                }
                if (label == null) {
                    Path t = dev.resolve("temp1_input");
                    if (Files.exists(t)) {
                        results.add(t);
                    }
                } else {
                    try (Stream<Path> labels = Files.list(dev)) {
                        List<Path> labelFiles = labels
                                .filter(p -> p.getFileName().toString().matches("temp\\d+_label"))
                                .sorted()
                                .toList();
                        for (Path lf : labelFiles) {
                            if (readTrim(lf).equals(label)) {
                                Path inp = Path.of(lf.toString().replace("_label", "_input"));
                                if (Files.exists(inp)) {
                                    results.add(inp);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return results;
    }

    private List<Path> discoverCpuCores() {
        List<Path> amdCores = new ArrayList<>();
        amdCores.addAll(findAllHwmon("k10temp", "Tccd1"));
        amdCores.addAll(findAllHwmon("k10temp", "Tccd2"));
        if (!amdCores.isEmpty()) {
            return amdCores;
        }
        List<Path> results = new ArrayList<>();
        if (!Files.isDirectory(HWMON_ROOT)) {
            return results;
        }
        try (Stream<Path> devs = Files.list(HWMON_ROOT)) {
            for (Path dev : devs.sorted().toList()) {
                Path nameFile = dev.resolve("name");
                if (!Files.exists(nameFile) || !readTrim(nameFile).equals("coretemp")) {
                    continue;
                }
                try (Stream<Path> labels = Files.list(dev)) {
                    List<Path> labelFiles = labels
                            .filter(p -> p.getFileName().toString().matches("temp\\d+_label"))
                            .sorted((a, b) -> Integer.compare(tempIndex(a), tempIndex(b)))
                            .toList();
                    for (Path lf : labelFiles) {
                        String label = readTrim(lf);
                        if (label.startsWith("Core ")) {
                            Path inp = Path.of(lf.toString().replace("_label", "_input"));
                            if (Files.exists(inp)) {
                                results.add(inp);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return results;
    }

    private static int tempIndex(Path labelFile) {
        String name = labelFile.getFileName().toString(); // "tempN_label"
        String digits = name.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    private List<String> discoverWholeDisks() {
        Path sysBlock = Path.of("/sys/block");
        if (!Files.isDirectory(sysBlock)) {
            return List.of();
        }
        try (Stream<Path> devs = Files.list(sysBlock)) {
            return devs.map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("loop") && !n.startsWith("ram"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String readTrim(Path p) {
        try {
            return Files.readString(p).strip();
        } catch (IOException e) {
            return "";
        }
    }

    private static Double readTempC(Path path) {
        if (path == null) {
            return null;
        }
        try {
            long milliC = Long.parseLong(readTrim(path));
            return Math.round(milliC / 1000.0 * 10) / 10.0;
        } catch (Exception e) {
            return null;
        }
    }

    private Long readRaplUj() {
        if (!raplReadable) {
            return null;
        }
        try {
            return Long.parseLong(readTrim(RAPL_ENERGY));
        } catch (Exception e) {
            return null;
        }
    }

    // ── CPU% via delta de /proc/stat (equivalente a psutil.cpu_percent(interval=None)) ──

    private Double readCpuDelta() {
        try {
            String line = Files.readAllLines(Path.of("/proc/stat")).get(0); // "cpu  u n s i iow irq sirq steal ..."
            String[] parts = line.trim().split("\\s+");
            long[] v = new long[parts.length - 1];
            for (int i = 1; i < parts.length; i++) {
                v[i - 1] = Long.parseLong(parts[i]);
            }
            long idle = v[3] + (v.length > 4 ? v[4] : 0); // idle + iowait
            long total = 0;
            for (long x : v) {
                total += x;
            }
            synchronized (cpuLock) {
                long prevTotal = prevCpuTotal, prevIdle = prevCpuIdle;
                prevCpuTotal = total;
                prevCpuIdle = idle;
                if (prevTotal < 0) {
                    return 0.0;
                }
                long totalDelta = total - prevTotal;
                long idleDelta = idle - prevIdle;
                if (totalDelta <= 0) {
                    return 0.0;
                }
                double pct = 100.0 * (totalDelta - idleDelta) / totalDelta;
                return Math.round(pct * 10) / 10.0;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private Double readCpuFreqMhz() {
        Path p = Path.of("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq");
        if (!Files.exists(p)) {
            return null;
        }
        try {
            long khz = Long.parseLong(readTrim(p));
            return Math.round(khz / 1000.0 * 10) / 10.0;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Disco: delta de /proc/diskstats (equivalente a psutil.disk_io_counters) ──

    private double[] readDiskDelta() {
        try {
            long readSectors = 0, writeSectors = 0;
            for (String line : Files.readAllLines(Path.of("/proc/diskstats"))) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 10) {
                    continue;
                }
                String name = f[2];
                if (!wholeDiskNames.contains(name)) {
                    continue;
                }
                readSectors += Long.parseLong(f[5]);
                writeSectors += Long.parseLong(f[9]);
            }
            long readBytes = readSectors * 512L;
            long writeBytes = writeSectors * 512L;
            long now = System.nanoTime();
            synchronized (diskLock) {
                long prevRead = prevReadBytes, prevWrite = prevWriteBytes, prevNanos = prevDiskNanos;
                prevReadBytes = readBytes;
                prevWriteBytes = writeBytes;
                prevDiskNanos = now;
                if (prevNanos < 0) {
                    return new double[]{0.0, 0.0};
                }
                double dtSeconds = (now - prevNanos) / 1e9;
                if (dtSeconds <= 0) {
                    return new double[]{0.0, 0.0};
                }
                double readMbS = Math.max(0.0, (readBytes - prevRead) / dtSeconds / (1024.0 * 1024.0));
                double writeMbS = Math.max(0.0, (writeBytes - prevWrite) / dtSeconds / (1024.0 * 1024.0));
                return new double[]{Math.round(readMbS * 100) / 100.0, Math.round(writeMbS * 100) / 100.0};
            }
        } catch (Exception e) {
            return new double[]{Double.NaN, Double.NaN};
        }
    }

    // ── Memoria via /proc/meminfo ─────────────────────────────────────────

    private record MemInfo(double usedGb, double availGb, double percent) {}

    private MemInfo readMemInfo() {
        try {
            Map<String, Long> kv = new LinkedHashMap<>();
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                String[] parts = line.split(":");
                if (parts.length < 2) {
                    continue;
                }
                String key = parts[0].trim();
                String valPart = parts[1].trim().replace(" kB", "").trim();
                try {
                    kv.put(key, Long.parseLong(valPart) * 1024L); // kB -> bytes
                } catch (NumberFormatException ignored) {
                }
            }
            long total = kv.getOrDefault("MemTotal", 0L);
            long avail = kv.getOrDefault("MemAvailable", 0L);
            long used = total - avail;
            double usedGb = Math.round(used / Math.pow(1024, 3) * 100) / 100.0;
            double availGb = Math.round(avail / Math.pow(1024, 3) * 100) / 100.0;
            double percent = total > 0 ? Math.round((double) used / total * 1000) / 10.0 : 0.0;
            return new MemInfo(usedGb, availGb, percent);
        } catch (Exception e) {
            return new MemInfo(0, 0, 0);
        }
    }

    // ── Snapshot publico (GET /api/metrics) ───────────────────────────────

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp_s", System.currentTimeMillis() / 1000.0);

        m.put("cpu_percent", readCpuDelta());
        m.put("cpu_freq_mhz", readCpuFreqMhz());
        m.put("cpu_temp_sensor", cpuSensorName);
        m.put("cpu_temp_tctl_c", readTempC(cpuTctl));
        m.put("cpu_temp_cores_c", cpuCores.stream().map(HardwareInfoService::readTempC).toList());

        MemInfo mem = readMemInfo();
        m.put("mem_used_gb", mem.usedGb());
        m.put("mem_avail_gb", mem.availGb());
        m.put("mem_percent", mem.percent());
        m.put("ram_temps_c", ramTemps.stream().map(HardwareInfoService::readTempC).toList());

        double[] disk = readDiskDelta();
        m.put("disk_read_mb_s", Double.isNaN(disk[0]) ? null : disk[0]);
        m.put("disk_write_mb_s", Double.isNaN(disk[1]) ? null : disk[1]);
        m.put("nvme_temps_c", nvmeTemps.stream().map(HardwareInfoService::readTempC).toList());

        m.put("gpu_edge_c", readTempC(gpuEdge));
        m.put("gpu_junction_c", readTempC(gpuJunction));
        m.put("gpu_mem_c", readTempC(gpuMem));

        m.put("acpitz_temp_c", readTempC(acpitzTemp));
        m.put("wifi_temp_c", readTempC(wifiTemp));

        m.put("rapl_energy_uj", readRaplUj());
        return m;
    }

    // ── Informacao estatica de sensores (GET /api/server-info) ────────────

    public Map<String, Object> sensorsInfo() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("cpu_sensor", cpuSensorName);
        s.put("has_cpu_cores", !cpuCores.isEmpty());
        s.put("n_cpu_cores", cpuCores.size());
        s.put("n_nvme", nvmeTemps.size());
        s.put("has_ram_temp", !ramTemps.isEmpty());
        s.put("has_gpu", gpuEdge != null);
        // Campos aditivos (nao existiam no backend Python original): IDs
        // estaveis por sensor, para o frontend nao precisar mais derivar a
        // chave "fatiando" o texto do label exibido.
        s.put("cpu_core_ids", indexedIds("cpu_core", cpuCores.size()));
        s.put("nvme_ids", indexedIds("nvme", nvmeTemps.size()));
        s.put("ram_ids", indexedIds("ram", ramTemps.size()));
        s.put("gpu_ids", gpuEdge != null ? List.of("gpu_1") : List.of());
        return s;
    }

    private static List<String> indexedIds(String prefix, int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(prefix + "_" + (i + 1));
        }
        return ids;
    }

    // ── GET /api/server-info ───────────────────────────────────────────────

    public Map<String, Object> serverInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("cpu_model", readCpuModel());
        info.put("cpu_physical", countPhysicalCores());
        info.put("cpu_logical", Runtime.getRuntime().availableProcessors());
        info.put("mem_total_gb", readMemTotalGb());
        info.put("sensors", sensorsInfo());
        return info;
    }

    private static String readCpuModel() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.startsWith("model name")) {
                    int idx = line.indexOf(':');
                    return idx >= 0 ? line.substring(idx + 1).trim() : "Desconhecido";
                }
            }
        } catch (IOException ignored) {
        }
        return "Desconhecido";
    }

    private static int countPhysicalCores() {
        try {
            Set<String> ids = new LinkedHashSet<>();
            String physicalId = null, coreId = null;
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.isBlank()) {
                    physicalId = null;
                    coreId = null;
                    continue;
                }
                if (line.startsWith("physical id")) {
                    physicalId = valueAfterColon(line);
                } else if (line.startsWith("core id")) {
                    coreId = valueAfterColon(line);
                }
                if (physicalId != null && coreId != null) {
                    ids.add(physicalId + ":" + coreId);
                }
            }
            return ids.isEmpty() ? Runtime.getRuntime().availableProcessors() : ids.size();
        } catch (IOException e) {
            return Runtime.getRuntime().availableProcessors();
        }
    }

    private static String valueAfterColon(String line) {
        int idx = line.indexOf(':');
        return idx >= 0 ? line.substring(idx + 1).trim() : null;
    }

    private static double readMemTotalGb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemTotal")) {
                    String valPart = line.split(":")[1].trim().replace(" kB", "").trim();
                    long bytes = Long.parseLong(valPart) * 1024L;
                    return Math.round(bytes / Math.pow(1024, 3) * 10) / 10.0;
                }
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }
}
