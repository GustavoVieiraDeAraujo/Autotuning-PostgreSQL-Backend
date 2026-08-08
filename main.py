"""
API JSON para controle e monitoramento do pipeline de autotuning TPC-H + TPC-DS.

Este serviço é o sucessor "sem HTML" de `web/app.py` do monorepo original:
faz a mesma orquestração (spawna os scripts `cli/*.py` do repositório
"pipeline" como subprocessos, faz tail dos logs via SSE), mas não serve
mais nenhuma página HTML — isso agora é responsabilidade do repositório
"frontend", que consome estes endpoints via HTTP (com CORS habilitado).

Fila e resultados (antes `data/queue.json` e `data/raw/*.json`) agora vivem
no Postgres de controle da pipeline (ver db/schema.sql no repo Pipeline) —
esta API lê/escreve lá diretamente via `DATABASE_URL`, não mais arquivos.

Uso
---
    uvicorn main:app --reload --port 8000

Variáveis de ambiente
----------------------
    PIPELINE_ROOT   Caminho para a raiz do repositório "pipeline" (que contém
                     logs/, cli/, etc). Padrão: "../Autotuning-PostgreSQL-Pipeline"
                     resolvido em relação a este repositório.
    DATABASE_URL    Connection string do Postgres de controle (fila + resultados).
                     Padrão: mesmo default do repo Pipeline (ver utils/db.py) —
                     assume o `db/docker-compose.yml` de lá rodando localmente.

Endpoints
---------
    GET  /api/metrics                    → snapshot de métricas de hardware (poll a cada 1s)
    GET  /api/server-info                → informações estáticas do servidor

    GET  /api/queue                      → estado atual da fila (Postgres)
    POST /api/reset                      → limpa fila+resultados (Postgres), logs e containers

    GET  /api/images/status              → status das imagens Docker necessárias

    GET  /api/results/list               → lista de tarefas com resultado disponível
    GET  /api/results/{tier}/{combo}/{id} → resultado completo de uma tarefa (por task_id)

    GET  /api/prepare/status             → se o prepare de imagens está em execução
    POST /api/prepare/start              → constrói as imagens Docker base
    POST /api/prepare/stop               → interrompe o prepare

    GET  /api/generator/status           → se o gerador está em execução
    POST /api/generator/start            → inicia geração de configurações
    POST /api/generator/stop             → interrompe o gerador

    GET  /api/runner/status              → se o runner está em execução
    POST /api/runner/start               → inicia execução da fila
    POST /api/runner/stop                → interrompe o runner

    GET  /stream/generate                → SSE com saída ao vivo (generate.log)
    GET  /stream/prepare                 → SSE com saída ao vivo (prepare.log)
    GET  /stream/runner                  → SSE com saída ao vivo (runner.log)
"""

import argparse
import asyncio
import base64
import os
import signal
import subprocess
import sys
from pathlib import Path

import docker
import docker.errors
import psycopg
from psycopg.rows import dict_row

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse

# ---------------------------------------------------------------------------
# Pipeline package (repositório irmão, instalado em modo editável — ver
# requirements.txt: `-e ../Autotuning-PostgreSQL-Pipeline`).
# ---------------------------------------------------------------------------

from monitoring import snapshot as hw_snapshot  # noqa: E402
# Nota: o código original (web/app.py) já acessava diretamente globals
# privados (prefixados com "_") do módulo monitoring.collector para montar
# o payload de /api/server-info. Isso é um "wart" conhecido do código
# original — replicado aqui de propósito, não é algo para corrigir nesta
# extração.
from monitoring.collector import (  # noqa: E402
    _CPU_SENSOR_NAME,
    _CPU_CORES,
    _NVME_TEMPS,
    _RAM_TEMPS,
    _GPU_EDGE,
)
from benchmarks.image_builder import TIER_IMAGE_TAGS  # noqa: E402
from utils.db import get_dsn  # noqa: E402 — mesmo DATABASE_URL/default da pipeline


async def _db() -> psycopg.AsyncConnection:
    """Abre uma conexão assíncrona nova com o Postgres de controle.

    De curta duração (uma por request) — carga é baixa (poll de fila a cada
    poucos segundos), não vale a pena manter um pool para isso aqui.
    """
    return await psycopg.AsyncConnection.connect(get_dsn(), row_factory=dict_row, autocommit=True)

# ---------------------------------------------------------------------------
# Paths — tudo derivado de PIPELINE_ROOT, nunca hardcoded para o monorepo.
# ---------------------------------------------------------------------------

_HERE = Path(__file__).parent
_PIPELINE_ROOT = Path(
    os.environ.get("PIPELINE_ROOT", str(_HERE / ".." / "Autotuning-PostgreSQL-Pipeline"))
).resolve()

_LOG_GENERATE = _PIPELINE_ROOT / "logs" / "generate.log"
_LOG_PREPARE = _PIPELINE_ROOT / "logs" / "prepare.log"
_LOG_RUNNER = _PIPELINE_ROOT / "logs" / "runner.log"

_LOCK_PATH = _PIPELINE_ROOT / "data" / ".runner.lock"

_GENERATE_SCRIPT = _PIPELINE_ROOT / "cli" / "generate.py"
_PREPARE_SCRIPT = _PIPELINE_ROOT / "cli" / "prepare.py"
_RUNNER_SCRIPT = _PIPELINE_ROOT / "cli" / "run.py"

# Achata TIER_IMAGE_TAGS ({benchmark: {tier: tag}}) na lista de tags
# necessárias. Isso substitui a lista `_REQUIRED_IMAGES` que o web/app.py
# original hand-duplicava — aqui ela é sempre derivada da fonte de verdade
# real (benchmarks/image_builder.py do pacote pipeline), então não pode
# ficar dessincronizada.
_REQUIRED_IMAGES = [
    tag for tiers in TIER_IMAGE_TAGS.values() for tag in tiers.values()
]

# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------

app = FastAPI(title="PostgreSQL Autotuning API", docs_url=None, redoc_url=None)

# CORS: para desenvolvimento local, liberamos qualquer origem. O frontend
# agora roda como um site estático separado (porta diferente), então
# CORS é necessário aqui — o web/app.py original não tinha nenhum, pois
# frontend e backend eram same-origin.
#
# ATENÇÃO: `allow_origins=["*"]` é adequado apenas para desenvolvimento.
# Em qualquer deployment real, isso deve ser restrito à(s) origem(ns)
# exata(s) do frontend (ex.: ["https://autotuning.example.com"]).
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------------------------------------------------------------------------
# API — fila
# ---------------------------------------------------------------------------

@app.get("/api/metrics")
async def get_metrics():
    """Retorna snapshot atual de métricas de hardware do servidor."""
    return hw_snapshot()


@app.get("/api/server-info")
async def get_server_info():
    """Retorna informações estáticas do servidor (CPU, RAM, sensores disponíveis)."""
    import psutil
    try:
        cpu_model = (Path("/proc/cpuinfo").read_text()
                     .split("model name")[1].split(":")[1].split("\n")[0].strip())
    except Exception:
        cpu_model = "Desconhecido"

    mem_total_gb = round(psutil.virtual_memory().total / 1024**3, 1)
    cpu_physical = psutil.cpu_count(logical=False) or 0
    cpu_logical = psutil.cpu_count(logical=True) or 0

    sensors = {
        "cpu_sensor": _CPU_SENSOR_NAME,
        "has_cpu_cores": len(_CPU_CORES) > 0,
        "n_cpu_cores": len(_CPU_CORES),
        "n_nvme": len(_NVME_TEMPS),
        "has_ram_temp": len(_RAM_TEMPS) > 0,
        "has_gpu": _GPU_EDGE is not None,
    }

    return {
        "cpu_model": cpu_model,
        "cpu_physical": cpu_physical,
        "cpu_logical": cpu_logical,
        "mem_total_gb": mem_total_gb,
        "sensors": sensors,
    }


@app.get("/api/queue")
async def get_queue():
    """Retorna a lista completa de tasks da fila (Postgres)."""
    async with await _db() as conn:
        cur = await conn.execute(
            """
            SELECT id, combination, tier, config, repetition, status, retry_count,
                   abandoned_reason, error, result_summary AS result
            FROM tasks ORDER BY id
            """
        )
        tasks = await cur.fetchall()
    if not _runner_running():
        for task in tasks:
            if task.get("status") == "running":
                task["status"] = "pending"
    return tasks


# ---------------------------------------------------------------------------
# API — reset
# ---------------------------------------------------------------------------

def _remove_benchmark_containers() -> list[str]:
    """Para e remove todos os containers de benchmark e build criados pelo sistema."""
    removed: list[str] = []
    prefixes = ("tpch_bench_", "tpcds_bench_", "tpch-build-tmp-", "tpcds-build-tmp-")
    try:
        client = docker.from_env(timeout=30)
        try:
            for c in client.containers.list(all=True):
                name = c.name or ""
                if any(name.startswith(p) for p in prefixes):
                    try:
                        c.remove(force=True)
                        removed.append(name)
                    except docker.errors.APIError:
                        pass
        finally:
            client.close()
    except Exception:
        pass
    return removed


@app.post("/api/reset")
async def reset_all():
    """Remove a fila, resultados (Postgres), logs e containers de benchmark."""
    if _generator_running():
        return JSONResponse({"error": "Gerador está em execução. Pare antes de resetar."}, status_code=409)
    if _prepare_running():
        return JSONResponse({"error": "Prepare está em execução. Pare antes de resetar."}, status_code=409)
    if _runner_running() or _LOCK_PATH.exists():
        return JSONResponse({"error": "Fila em execução. Aguarde terminar antes de resetar."}, status_code=409)

    removed = []

    # Remove containers de benchmark parados ou em execução
    containers_removed = _remove_benchmark_containers()
    removed.extend(containers_removed)

    # Limpa fila e resultados — CASCADE também limpa task_results
    async with await _db() as conn:
        await conn.execute("TRUNCATE tasks RESTART IDENTITY CASCADE")
    removed.append("tasks + task_results (Postgres)")

    for log in (_LOG_GENERATE, _LOG_PREPARE, _LOG_RUNNER):
        if log.exists():
            log.unlink()
            removed.append(log.name)

    return {"status": "ok", "removed": removed}


# ---------------------------------------------------------------------------
# API — status das imagens Docker
# ---------------------------------------------------------------------------

@app.get("/api/images/status")
async def images_status():
    """Verifica se as imagens Docker necessárias estão disponíveis."""
    try:
        client = docker.from_env(timeout=10)
        try:
            available: set[str] = set()
            for img in client.images.list():
                for tag in (img.tags or []):
                    available.add(tag)
            status = {tag: tag in available for tag in _REQUIRED_IMAGES}
            return {"ready": all(status.values()), "images": status}
        finally:
            client.close()
    except Exception as e:
        return {"ready": False, "images": {}, "error": str(e)}


# ---------------------------------------------------------------------------
# API — resultados
# ---------------------------------------------------------------------------

@app.get("/api/results/list")
async def list_results():
    """Lista todas as tarefas com resultado disponível (Postgres)."""
    async with await _db() as conn:
        cur = await conn.execute(
            """
            SELECT t.id AS task_id, t.tier, t.combination
            FROM tasks t JOIN task_results r ON r.task_id = t.id
            ORDER BY t.id
            """
        )
        rows = await cur.fetchall()
    return {"files": [
        {"task_id": r["task_id"], "tier": r["tier"], "combo": r["combination"]}
        for r in rows
    ]}


@app.get("/api/results/{tier}/{combo}/{task_id}")
async def get_result(tier: str, combo: str, task_id: int):
    """Retorna o resultado completo de uma tarefa específica (Postgres).

    Junta ``tasks`` (metadados/config/status) e ``task_results`` (conteúdo
    do benchmark) num único objeto — mesmo formato "achatado" que o antigo
    arquivo ``task_{id}.json`` tinha, para não exigir mudanças no frontend
    além de como a URL é montada (por task_id, não mais por nome de arquivo).
    """
    async with await _db() as conn:
        cur = await conn.execute(
            """
            SELECT t.id AS task_id, t.tier, t.combination, t.status,
                   t.abandoned_reason, t.error, t.config AS pg_config,
                   r.started_at, r.finished_at, r.duration_s,
                   r.tpc_h, r.tpc_ds, r.hw_metrics
            FROM tasks t JOIN task_results r ON r.task_id = t.id
            WHERE t.id = %(task_id)s AND t.tier = %(tier)s AND t.combination = %(combo)s
            """,
            {"task_id": task_id, "tier": tier, "combo": combo},
        )
        row = await cur.fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Tarefa não encontrada.")
    return row


# ---------------------------------------------------------------------------
# API — controle do prepare
# ---------------------------------------------------------------------------

_prepare_proc: subprocess.Popen | None = None


def _prepare_running() -> bool:
    return _prepare_proc is not None and _prepare_proc.poll() is None


@app.get("/api/prepare/status")
async def prepare_status():
    """Retorna se o prepare de imagens está em execução."""
    return {
        "running": _prepare_running(),
        "pid": _prepare_proc.pid if _prepare_running() else None,
    }


@app.post("/api/prepare/start")
async def prepare_start(force: bool = False):
    """Inicia a construção das imagens Docker necessárias."""
    global _prepare_proc
    if _prepare_running():
        return JSONResponse({"error": "Prepare já está em execução."}, status_code=409)
    if _runner_running():
        return JSONResponse({"error": "O runner está em execução. Aguarde terminar."}, status_code=409)
    cmd = [sys.executable, str(_PREPARE_SCRIPT)]
    if force:
        cmd.append("--force")
    _LOG_PREPARE.parent.mkdir(parents=True, exist_ok=True)
    _err = open(_LOG_PREPARE, "ab")  # noqa: SIM115
    _prepare_proc = subprocess.Popen(
        cmd,
        cwd=str(_PIPELINE_ROOT),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=_err,
    )
    _err.close()
    return {"status": "started", "pid": _prepare_proc.pid}


@app.post("/api/prepare/stop")
async def prepare_stop():
    """Interrompe o prepare."""
    if not _prepare_running():
        return JSONResponse({"error": "Prepare não está em execução."}, status_code=409)
    _prepare_proc.send_signal(signal.SIGINT)
    return {"status": "stopping"}


# ---------------------------------------------------------------------------
# API — controle do gerador
# ---------------------------------------------------------------------------

_generator_proc: subprocess.Popen | None = None


def _generator_running() -> bool:
    return _generator_proc is not None and _generator_proc.poll() is None


@app.get("/api/generator/status")
async def generator_status():
    """Retorna se o gerador de configurações está em execução."""
    return {
        "running": _generator_running(),
        "pid": _generator_proc.pid if _generator_running() else None,
    }


@app.post("/api/generator/start")
async def generator_start(
    n_configs: int = 51,
    seed: int | None = None,
):
    """Inicia a geração de configurações como subprocesso.

    Args:
        n_configs: Número de configs por combinação (padrão: 51).
                   Use 3 para rodada de validação rápida.
        seed:      Semente LHS para reprodutibilidade (padrão: None).
    """
    global _generator_proc
    if n_configs < 3 or n_configs % 3 != 0:
        return JSONResponse(
            {"error": f"n_configs deve ser múltiplo de 3 e ≥ 3; recebido: {n_configs}"},
            status_code=422,
        )
    if _generator_running():
        return JSONResponse({"error": "Gerador já está em execução."}, status_code=409)
    if _runner_running():
        return JSONResponse({"error": "O runner está em execução. Aguarde terminar."}, status_code=409)
    cmd = [sys.executable, str(_GENERATE_SCRIPT), "--n-configs", str(n_configs)]
    if seed is not None:
        cmd += ["--seed", str(seed)]
    _LOG_GENERATE.parent.mkdir(parents=True, exist_ok=True)
    _err = open(_LOG_GENERATE, "ab")  # noqa: SIM115
    _generator_proc = subprocess.Popen(
        cmd,
        cwd=str(_PIPELINE_ROOT),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=_err,
    )
    _err.close()
    return {"status": "started", "pid": _generator_proc.pid, "n_configs": n_configs}


@app.post("/api/generator/stop")
async def generator_stop():
    """Interrompe o gerador."""
    if not _generator_running():
        return JSONResponse({"error": "Gerador não está em execução."}, status_code=409)
    _generator_proc.send_signal(signal.SIGINT)
    return {"status": "stopping"}


# ---------------------------------------------------------------------------
# API — controle do runner
# ---------------------------------------------------------------------------

_runner_proc: subprocess.Popen | None = None


def _runner_running() -> bool:
    return _runner_proc is not None and _runner_proc.poll() is None


@app.get("/api/runner/status")
async def runner_status():
    """Retorna se o runner está em execução."""
    return {
        "running": _runner_running(),
        "pid": _runner_proc.pid if _runner_running() else None,
    }


@app.post("/api/runner/start")
async def runner_start():
    """Inicia a execução da fila de benchmarks."""
    global _runner_proc
    if _runner_running():
        return JSONResponse({"error": "Runner já está em execução."}, status_code=409)
    if _generator_running():
        return JSONResponse({"error": "O gerador está em execução. Aguarde terminar."}, status_code=409)
    async with await _db() as conn:
        cur = await conn.execute("SELECT NOT EXISTS (SELECT 1 FROM tasks) AS empty")
        empty = (await cur.fetchone())["empty"]
    if empty:
        return JSONResponse({"error": "Fila não encontrada. Gere as configurações primeiro."}, status_code=400)
    _LOG_RUNNER.parent.mkdir(parents=True, exist_ok=True)
    _err = open(_LOG_RUNNER, "ab")  # noqa: SIM115
    _runner_proc = subprocess.Popen(
        [sys.executable, str(_RUNNER_SCRIPT)],
        cwd=str(_PIPELINE_ROOT),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=_err,
    )
    _err.close()
    return {"status": "started", "pid": _runner_proc.pid}


@app.post("/api/runner/stop")
async def runner_stop():
    """Para o runner. A task atual volta para pending na próxima execução."""
    if not _runner_running():
        return JSONResponse({"error": "Runner não está em execução."}, status_code=409)
    _runner_proc.send_signal(signal.SIGINT)
    return {"status": "stopping"}


# ---------------------------------------------------------------------------
# SSE — terminals ao vivo
# ---------------------------------------------------------------------------

def _make_log_streamer(log_path: Path):
    """Cria um gerador SSE que serve um arquivo de log em tempo real.

    Cada evento SSE contém um chunk do log codificado em base64 para
    preservar os códigos ANSI que o xterm.js (no frontend) renderiza.
    """
    async def generate():
        pos = 0

        if log_path.exists():
            with open(log_path, "rb") as f:
                data = f.read()
            if data:
                yield f"data: {base64.b64encode(data).decode()}\n\n"
            pos = len(data)

        while True:
            await asyncio.sleep(0.15)

            if not log_path.exists():
                yield ": keepalive\n\n"
                continue

            size = log_path.stat().st_size
            if size < pos:
                # Log foi truncado (nova execução)
                pos = 0
                yield "event: reset\ndata: reset\n\n"
                continue

            if size > pos:
                with open(log_path, "rb") as f:
                    f.seek(pos)
                    chunk = f.read()
                pos += len(chunk)
                yield f"data: {base64.b64encode(chunk).decode()}\n\n"
            else:
                yield ": keepalive\n\n"

    return generate


@app.get("/stream/generate")
async def stream_generate() -> StreamingResponse:
    """Streaming do generate.log (geração da fila + LHS)."""
    return StreamingResponse(
        _make_log_streamer(_LOG_GENERATE)(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.get("/stream/prepare")
async def stream_prepare() -> StreamingResponse:
    """Streaming do prepare.log (construção das imagens Docker)."""
    return StreamingResponse(
        _make_log_streamer(_LOG_PREPARE)(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.get("/stream/runner")
async def stream_runner() -> StreamingResponse:
    """Streaming do runner.log (execução dos benchmarks)."""
    return StreamingResponse(
        _make_log_streamer(_LOG_RUNNER)(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="PostgreSQL Autotuning — API Gateway")
    parser.add_argument("--host", default="0.0.0.0",
                        help="Host para bind (padrão: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8000,
                        help="Porta HTTP (padrão: 8000)")
    args = parser.parse_args()

    print(f"[api] PIPELINE_ROOT = {_PIPELINE_ROOT}")
    print(f"[api] Iniciando em http://{args.host}:{args.port}")
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")
