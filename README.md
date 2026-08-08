# Autotuning PostgreSQL — Backend

Gateway HTTP (REST + SSE) que orquestra a pipeline de autotuning PostgreSQL
(repositório irmão [`Autotuning-PostgreSQL-Pipeline`](../Autotuning-PostgreSQL-Pipeline),
em Python) e serve dados de fila/resultados do Postgres de controle.

Java 21 + Spring Boot 3. Sucessor do antigo backend em Python/FastAPI —
mesmo contrato REST/SSE, mesma responsabilidade: orquestrar os scripts
`cli/*.py` da Pipeline como subprocessos e ler/escrever a fila e os
resultados no Postgres de controle (`db/schema.sql`, na Pipeline).

## Rodando localmente

Pré-requisitos: Java 21, Maven, e o Postgres de controle da Pipeline
rodando (`make db-up` no repositório Pipeline).

```bash
mvn spring-boot:run
```

Sobe em `http://localhost:8000` por padrão.

## Variáveis de ambiente

| Variável | Padrão | Descrição |
|---|---|---|
| `PIPELINE_ROOT` | `../Autotuning-PostgreSQL-Pipeline` | Raiz do repositório Pipeline (scripts `cli/*.py`, logs) |
| `DATABASE_URL` | `postgresql://autotuning:autotuning@localhost:5433/autotuning_queue` | Connection string (formato libpq) do Postgres de controle |
| `PORT` | `8000` | Porta HTTP |

## Endpoints

Ver `docs/` (ou o código dos controllers em `src/main/java/com/autotuning/backend/`) —
mesma superfície do backend Python original: `/api/queue`, `/api/reset`,
`/api/images/status`, `/api/results/list`, `/api/results/{tier}/{combo}/{taskId}`,
`/api/{generator,prepare,runner}/{status,start,stop}`, `/stream/{generate,prepare,runner}`,
`/api/metrics`, `/api/server-info`.

## Decisões de arquitetura

- **Spring MVC (não WebFlux)** — a carga é poucas conexões SSE de longa
  duração + polling de baixa frequência, não alta concorrência; WebFlux só
  compensaria com um driver Postgres reativo (R2DBC), complexidade sem
  benefício nesse tamanho de serviço.
- **JdbcTemplate (não JPA/Hibernate)** — as colunas JSONB (`config`,
  `result_summary`, `tpc_h`, `tpc_ds`, `hw_metrics`) ficam opacas de
  propósito, já que quem define e evolui o formato interno é a Pipeline
  (Python), não este serviço.
- **Métricas de hardware (`/api/metrics`, `/api/server-info`) são uma porta
  nativa** de `monitoring/collector.py` da Pipeline — leem os mesmos
  arquivos de `/proc` e `/sys`, sem shell-out pro Python (endpoint de maior
  frequência do backend, poll de 1s por navegador conectado).
- **`ProcessSupervisor.stop()` manda `kill -INT`, não `Process.destroy()`**
  — os scripts `cli/*.py` da Pipeline só tratam `SIGINT` especificamente
  (`signal.signal(signal.SIGINT, ...)`); `destroy()` manda `SIGTERM` e
  pularia o encerramento gracioso deles.
