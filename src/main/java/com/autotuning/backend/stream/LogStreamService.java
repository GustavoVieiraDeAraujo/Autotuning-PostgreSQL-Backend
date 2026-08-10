package com.autotuning.backend.stream;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streaming ao vivo de um arquivo de log via SSE: porta de
 * {@code _make_log_streamer} do backend Python original.
 *
 * <p>Ao conectar: le o conteudo atual do arquivo inteiro e manda como um
 * unico evento (base64, pra preservar codigos ANSI que o xterm.js no
 * frontend renderiza). Depois faz poll a cada 150ms: se o arquivo encolheu
 * (nova execucao truncou o log), manda {@code event: reset}; se cresceu,
 * manda so o delta; senao, manda um comentario de keepalive.
 *
 * <p>Cada conexao roda numa virtual thread propria (barata o suficiente pra
 * nao precisar de um pool dedicado): o loop verifica uma flag atomica,
 * setada pelos callbacks de completion/timeout/error do emitter, pra
 * encerrar a thread quando o cliente desconecta (sem isso a thread vazaria
 * pra sempre rodando em background).
 */
@Service
public class LogStreamService {

    private static final long POLL_INTERVAL_MS = 150;

    public SseEmitter stream(Path logFile) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = sem timeout
        AtomicBoolean stopped = new AtomicBoolean(false);

        emitter.onCompletion(() -> stopped.set(true));
        emitter.onTimeout(() -> stopped.set(true));
        emitter.onError(e -> stopped.set(true));

        Thread.ofVirtual().start(() -> pollLoop(logFile, emitter, stopped));

        return emitter;
    }

    private void pollLoop(Path logFile, SseEmitter emitter, AtomicBoolean stopped) {
        long pos = 0;
        try {
            if (Files.exists(logFile)) {
                byte[] data = Files.readAllBytes(logFile);
                if (data.length > 0) {
                    sendData(emitter, data);
                }
                pos = data.length;
            }

            while (!stopped.get()) {
                Thread.sleep(POLL_INTERVAL_MS);

                if (!Files.exists(logFile)) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    continue;
                }

                long size = Files.size(logFile);
                if (size < pos) {
                    // Log foi truncado (nova execucao)
                    pos = 0;
                    emitter.send(SseEmitter.event().name("reset").data("reset"));
                    continue;
                }

                if (size > pos) {
                    byte[] chunk = readFrom(logFile, pos, size - pos);
                    pos += chunk.length;
                    sendData(emitter, chunk);
                } else {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Cliente desconectou ou I/O falhou: encerra silenciosamente,
            // igual ao comportamento tolerante do gerador Python original.
            emitter.completeWithError(e);
        }
    }

    private static void sendData(SseEmitter emitter, byte[] raw) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(raw);
        // MediaType.TEXT_PLAIN explicito e obrigatorio aqui: sem isso, o
        // conversor de mensagens do Spring serializa a String como JSON
        // (adicionando aspas/escapes), o que quebra o atob() do frontend.
        emitter.send(SseEmitter.event().data(b64, MediaType.TEXT_PLAIN));
    }

    private static byte[] readFrom(Path file, long offset, long length) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            byte[] buf = new byte[(int) length];
            raf.readFully(buf);
            return buf;
        }
    }
}
