# Autotuning-PostgreSQL — API

Serviço FastAPI que expõe, como JSON puro, a orquestração do pipeline de
autotuning de PostgreSQL (TPC-H + TPC-DS): fila de tasks, métricas de
hardware, controle de geração/preparo/execução de benchmarks, e streaming
ao vivo dos logs via SSE.

Este repositório é a extração da camada de API do antigo `web/app.py`
(monorepo `Autotuning-PostgreSQL`), removendo a renderização de HTML — que
agora é responsabilidade de um repositório "frontend" separado, que
consome estes endpoints via HTTP com CORS habilitado.

## Como rodar

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

uvicorn main:app --reload --port 8000
```

Ou diretamente:

```bash
python main.py --host 0.0.0.0 --port 8000
```

## Variável de ambiente `PIPELINE_ROOT`

Este serviço não contém nenhuma lógica de pipeline — ele apenas:

1. importa funções do pacote `pipeline` (instalado em modo editável, ver
   `requirements.txt`: `-e ../Autotuning-PostgreSQL-Pipeline`) para
   chamadas diretas (`monitoring.snapshot()`, `TIER_IMAGE_TAGS`, etc.);
2. spawna os scripts `cli/generate.py`, `cli/prepare.py`, `cli/run.py`
   do repositório pipeline como subprocessos, para as operações de
   longa duração (gerar configs, construir imagens Docker, rodar a fila);
3. lê/escreve arquivos de estado (`data/queue.json`, `data/raw/*.json`,
   `logs/*.log`, `data/.runner.lock`) diretamente no filesystem do
   repositório pipeline.

Todos esses caminhos são derivados da variável de ambiente
`PIPELINE_ROOT`:

```bash
export PIPELINE_ROOT=/caminho/para/Autotuning-PostgreSQL-Pipeline
```

Padrão (se não definida): `../Autotuning-PostgreSQL-Pipeline`, resolvido
em relação à raiz deste repositório — ou seja, assume que os dois repos
são clonados lado a lado:

```
Desenvolvimento/Projetos/
  Autotuning-PostgreSQL-Api/          (este repo)
  Autotuning-PostgreSQL-Pipeline/     (repo irmão)
```

## Simplificação atual: co-localização na mesma máquina (temporário)

**Importante:** esta versão assume que a API e o pipeline rodam na
**mesma máquina**, compartilhando o mesmo filesystem — exatamente como o
`web/app.py` original fazia dentro do monorepo. A comunicação entre os
dois é feita via `subprocess.Popen` (para start/stop de generate/prepare/
run) e leitura direta de arquivos (fila, resultados, logs), não via rede.

Isso é uma simplificação conhecida e temporária, adotada apenas para
viabilizar a separação em 3 repositórios (pipeline / api / frontend) sem
reescrever a arquitetura de execução no mesmo passo. A arquitetura alvo
(documentada em outro lugar, fora do escopo deste repositório) deve
substituir esse acoplamento por uma API de rede real e uma fila
distribuída baseada em Postgres, permitindo que a execução do pipeline
aconteça em uma máquina totalmente diferente da API/frontend. Nenhum
coordenador/worker distribuído é implementado aqui — isso é trabalho
futuro deliberadamente fora de escopo desta extração.

## CORS

`CORSMiddleware` está habilitado com `allow_origins=["*"]` para
facilitar o desenvolvimento local do frontend (que agora roda em uma
origem/porta diferente da API). **Isso precisa ser restringido à(s)
origem(ns) exata(s) do frontend antes de qualquer deployment real**
(ex.: `allow_origins=["https://autotuning.example.com"]`).

## Endpoints

Ver docstring no topo de `main.py` para a lista completa — espelha,
endpoint a endpoint, o que `web/app.py` do monorepo original expunha sob
`/api/*` e `/stream/*`, exceto `GET /` (a página HTML), que foi removida
por não fazer mais sentido aqui.

## Diferenças em relação ao `web/app.py` original

- **Sem rotas HTML/estáticas** (`GET /`, Jinja2Templates, `StaticFiles`)
  — o frontend agora é um repositório/site separado.
- **CORS habilitado** — necessário agora que frontend e API não são mais
  same-origin.
- **`TIER_IMAGE_TAGS` importado do pacote pipeline** (`benchmarks.image_builder`)
  em vez de hardcoded duas vezes (`_REQUIRED_IMAGES` no `/api/reset` e em
  `/api/images/status`). O `web/app.py` original mantinha uma lista
  manual duplicada das 6 tags de imagem, que podia ficar dessincronizada
  da fonte real em `benchmarks/image_builder.py`. Aqui as tags usadas por
  `/api/reset` e `/api/images/status` são sempre derivadas de
  `TIER_IMAGE_TAGS` (achatado), eliminando essa classe de bug.
- Todos os caminhos de filesystem (`data/`, `logs/`, `cli/`) agora são
  derivados de `PIPELINE_ROOT` em vez de hardcoded para a raiz do
  monorepo.
