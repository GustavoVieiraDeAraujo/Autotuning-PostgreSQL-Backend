# Autotuning PostgreSQL: Backend

## Sumário

- [Papel deste repositório](#papel-deste-repositório)
- [Participantes](#participantes)
- [Tecnologias](#tecnologias)
- [Arquitetura: visão geral](#arquitetura-visão-geral)
- [Como funciona, em detalhe](#como-funciona-em-detalhe)
- [Endpoints da API](#endpoints-da-api)
- [Decisões de arquitetura](#decisões-de-arquitetura)
- [Validação e resultados](#validação-e-resultados)
- [Limitações](#limitações)
- [Estrutura do Projeto](#estrutura-do-projeto)
- [Requisitos](#requisitos)
- [Variáveis de ambiente](#variáveis-de-ambiente)
- [Como Executar](#como-executar)

## Papel deste repositório

Gateway HTTP (REST + SSE) que orquestra a pipeline de autotuning PostgreSQL
(repositório irmão [`Autotuning-PostgreSQL-Pipeline`](../Autotuning-PostgreSQL-Pipeline),
em Python) e serve dados de fila/resultados do Postgres de controle. A API
que o [`Autotuning-PostgreSQL-Frontend`](../Autotuning-PostgreSQL-Frontend)
consome pra dar visibilidade/controle web sobre a coleta de benchmark
(iniciar/parar geração de configs, preparo de imagens Docker, execução da
fila, acompanhar logs em tempo real via SSE, ver resultados).

Java 21 + Spring Boot 3. Reescrita completa (2026-08) do backend original em
Python/FastAPI, com o mesmo contrato REST/SSE e a mesma responsabilidade: orquestrar
os scripts `cli/*.py` da Pipeline como subprocessos e ler/escrever a fila e
os resultados no Postgres de controle (`db/schema.sql`, na Pipeline). A
reescrita foi decisão minha de stack (Java + Spring no back, React + TS
no front), não motivada por limitação técnica da versão anterior. Validada
ponta a ponta contra dado real (Pipeline gerando configs → Postgres → este
backend servindo → Frontend renderizando) antes de ser considerada completa.

Importante: este serviço **não executa** benchmarks nem treina o
meta-modelo: ele só orquestra e expõe. Toda a lógica de geração de
configurações (LHS), execução de TPC-H/TPC-DS em containers Docker e
treino do XGBoost/XGBRanker vive na Pipeline, em Python. O Backend nunca
interpreta esse conteúdo: as colunas JSONB da fila/resultados atravessam
como texto/JSON opaco (ver seção sobre `JsonbUtil` mais abaixo).

## Participantes

| Nome | Matricula |
|---|---|
| Gustavo Vieira de Araujo | 211068440 |

## Tecnologias

| Tecnologia | Uso |
|---|---|
| Java 21 | Linguagem principal, com threads virtuais nas conexões SSE |
| Spring Boot 3.3 (Spring MVC) | Framework do gateway HTTP REST + SSE |
| Spring JDBC (`JdbcTemplate`) | Acesso ao Postgres de controle sem ORM |
| HikariCP | Pool de conexões com o Postgres de controle |
| docker-java | Cliente Docker para subir/derrubar containers de benchmark |
| Lombok | Redução de boilerplate nas classes Java |
| Maven | Build e gerenciamento de dependências |
| PostgreSQL | Banco de controle da fila e dos resultados |

## Arquitetura: visão geral

O Backend é um único processo JVM com quatro grupos de responsabilidade que
se cruzam em pontos bem definidos: os **controllers REST/SSE** (a
superfície pública), o **`ProcessSupervisor`** (que sobe/derruba os
subprocessos Python da Pipeline), o **`HardwareInfoService`** (que lê
métricas de hardware nativamente, sem Python) e a **camada JDBC**
(`TaskDao`/`ResultsDao` via `JdbcTemplate`, contra o Postgres de controle).

![Arquitetura do Backend: controllers, ProcessSupervisor, HardwareInfoService e camada JDBC](docs/architecture.svg)

Pontos-chave do diagrama:

- O Frontend nunca fala diretamente com a Pipeline nem com o Postgres:
  tudo passa pelo Backend, inclusive os bytes de log dos processos Python.
- `ProcessSupervisor` é o único componente que sabe iniciar/derrubar
  processos do sistema operacional; os controllers de controle
  (`GeneratorController`, `PrepareController`, `RunnerController`) só
  montam a linha de comando e delegam.
- `HardwareInfoService` nunca faz shell-out para Python: lê os mesmos
  arquivos de `/proc`/`/sys` que `monitoring/collector.py` (Pipeline) lê,
  de forma independente.
- A comunicação entre Backend e Pipeline acontece por dois canais
  totalmente desacoplados: **subprocesso + arquivo de log** (para
  controle/observação) e **Postgres compartilhado** (para dado
  estruturado, fila e resultados). Não há chamada de rede entre os dois
  serviços.

## Como funciona, em detalhe

### 1. Streaming de log via SSE, de ponta a ponta

Cada um dos três processos de longa duração da Pipeline (`generate.py`,
`prepare.py`, `run.py`) escreve seu progresso em `stderr`, que o
`ProcessSupervisor` redireciona para um arquivo (`PIPELINE_ROOT/logs/<nome>.log`,
via `ProcessBuilder.Redirect.appendTo`). O Backend nunca lê a saída do
processo através de um pipe. Em vez disso, lê o arquivo de log de forma
independente, o que permite reconectar clientes SSE sem precisar que o
processo Python ainda esteja vivo (dá pra reabrir uma aba do navegador e
ver o log de uma execução que já terminou).

`LogStreamService.stream(logFile)` implementa isso como um `SseEmitter`
sem timeout (`new SseEmitter(0L)`), cujo loop de leitura roda numa
**virtual thread** dedicada (`Thread.ofVirtual().start(...)`), baratas o
suficiente (Java 21 + `spring.threads.virtual.enabled: true` no
`application.yml`) para não precisar de um pool de threads compartilhado
por conexão SSE.

Ao conectar, o serviço lê o arquivo inteiro (`Files.readAllBytes`) e manda
como um único evento. A cada 150 ms depois disso, ele compara o tamanho
atual do arquivo com a posição já enviada:

- **Cresceu** → lê só o delta (`RandomAccessFile.seek(pos).readFully(...)`)
  e manda como novo evento.
- **Encolheu** → o arquivo foi truncado por uma nova execução; o serviço
  reresseta a posição para 0 e emite um evento nomeado `reset`, que o
  frontend usa para limpar o terminal (xterm.js) antes de receber o
  conteúdo novo.
- **Sem mudança** → manda um comentário de keepalive, pra manter a conexão
  viva através de proxies/load balancers que fecham conexões ociosas.

Um detalhe de payload que quebra silenciosamente se esquecido: o conteúdo
do log é enviado em **Base64** (para preservar códigos de escape ANSI que
o terminal do frontend interpreta), mas `emitter.send(...)` precisa do
`MediaType.TEXT_PLAIN` explícito nesse envio. Sem isso, o
`HttpMessageConverter` padrão do Spring serializa a `String` Base64 como
**JSON**, ou seja, adiciona aspas ao redor e escapa caracteres, o que
corrompe a string antes mesmo de chegar ao `atob()` do navegador. Forçar
`TEXT_PLAIN` garante que o Base64 atravesse o `data:` do evento SSE sem
nenhuma transformação a mais.

Quando o cliente desconecta (fecha a aba, `EventSource.close()`, timeout de
rede), os callbacks `onCompletion`/`onTimeout`/`onError` do `SseEmitter`
setam uma flag atômica (`AtomicBoolean stopped`) que o loop da virtual
thread verifica a cada iteração. Sem isso, a thread de polling vazaria
para sempre, continuando a ler o arquivo mesmo sem ninguém do outro lado.

![Sequencia do streaming de log via SSE, de ponta a ponta](docs/sse_streaming_sequence.svg)

### 2. Como o `ProcessSupervisor` sobe e derruba os processos Python

`ProcessSupervisor` é o análogo Java dos globais `subprocess.Popen | None`
que o backend Python original mantinha para os três processos de longa
duração (`GENERATOR`, `PREPARE`, `RUNNER`, ver `ManagedProcessKind`). Ele
guarda um `Map<ManagedProcessKind, Process>` e, para cada `kind`, um
`ReentrantLock` próprio.

**Início (`start`)**: monta um `ProcessBuilder` com `directory(PIPELINE_ROOT)`,
`stdin` redirecionado para `/dev/null` (equivalente a `stdin=DEVNULL` no
Python), `stdout` descartado (`Redirect.DISCARD`) e `stderr` acrescentado
ao arquivo de log correspondente. O comando em si é montado pelos
controllers de controle chamando `paths.pythonExecutable()`, que resolve
para `PIPELINE_ROOT/.venv/bin/python` se essa venv existir (criada por
`make setup` na Pipeline), ou cai para `python3` do `PATH` caso contrário.

O lock por `kind` existe para resolver uma condição de corrida que **não
existia** no backend Python original: o FastAPI antigo rodava em um único
event loop `asyncio` (efetivamente single-threaded para essa lógica),
então duas requisições quase simultâneas de "iniciar gerador" já eram
naturalmente serializadas. O Spring MVC, ao contrário, atende requisições
em múltiplas threads. Sem o `ReentrantLock`, dois cliques rápidos em
"Gerar" no frontend poderiam passar os dois pela checagem
`isRunning() == false` antes de qualquer um marcar o processo como
iniciado, resultando em dois processos Python concorrentes sobre a mesma
fila.

**Parada (`stop`)**: aqui está uma decisão deliberada e documentada no
próprio código: `stop()` não usa `Process.destroy()` do Java, e sim monta
um `ProcessBuilder("kill", "-INT", pid)`. O motivo: `Process.destroy()`
manda `SIGTERM`, mas os scripts `cli/*.py` da Pipeline só instalam handler
explícito para `SIGINT` (`signal.signal(signal.SIGINT, ...)`). Um
`SIGTERM` não tratado especificamente pularia a rotina de encerramento
gracioso desses scripts (por exemplo, devolver a tarefa em andamento para
`pending` na fila antes de sair), deixando o sistema dependendo apenas da
reconciliação de lease feita depois, mais lenta e menos previsível do que
o encerramento correto via `SIGINT`.

### 3. Métricas de hardware coletadas nativamente em Java

`GET /api/metrics` (poll de 1 s por navegador conectado, usado pela aba
"Hardware" do frontend) e `GET /api/server-info` são servidos por
`HardwareInfoService`, que é uma porta linha-a-linha de
`monitoring/collector.py` da Pipeline, mas em Java puro, sem chamar
Python.

No `@PostConstruct` (`discoverSensors()`), o serviço varre
`/sys/class/hwmon/*` procurando pelos mesmos drivers que o `collector.py`
conhece (`k10temp`/`coretemp` para temperatura de CPU, `nvme`, `amdgpu`,
`spd5118` para RAM DDR5, `acpitz`, `iwlwifi`), guardando os caminhos dos
arquivos `tempN_input` já resolvidos. Também verifica a legibilidade de
`/sys/class/powercap/intel-rapl:0/energy_uj` (Intel RAPL, energia
acumulada) e enumera os discos físicos em `/sys/block` (excluindo `loop*`
e `ram*`).

A cada chamada a `snapshot()`, o serviço:

- Lê `/proc/stat` e calcula o delta de CPU% em relação à leitura anterior
  (o mesmo cálculo que `psutil.cpu_percent(interval=None)` faz por trás,
  daí o "aquecimento" de uma leitura descartada no `@PostConstruct`, para
  que a primeira leitura pública já seja válida).
- Lê `/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq` para a
  frequência atual da CPU.
- Lê `/proc/diskstats` e calcula MB/s de leitura/escrita por delta de
  setores, análogo a `psutil.disk_io_counters()`.
- Lê `/proc/meminfo` para memória usada/disponível.
- Lê os arquivos `tempN_input` descobertos no startup para cada sensor de
  temperatura (CPU, núcleos, NVMe, GPU, RAM, Wi-Fi, `acpitz`).
- Lê `energy_uj` do RAPL, se disponível.

Nada disso passa por `Runtime.exec()` ou qualquer chamada a um
interpretador externo: é leitura de arquivo texto pura via
`java.nio.file.Files`. Isso importa porque `/api/metrics` é, de longe, o
endpoint de maior frequência do backend: com vários navegadores
conectados fazendo poll de 1 s cada, spawnar um processo Python por
requisição seria uma dependência viva cara e desnecessária. A mesma
informação já está disponível como arquivo texto no kernel.

## Endpoints da API

Mesma superfície do backend Python original, apenas reorganizada aqui por
grupo funcional (o contrato de rota, verbo e formato de resposta não
mudou).

**Fluxo de geração/preparo/execução** (`ManagedProcessKind` correspondente,
via `ProcessSupervisor`):

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/generator/status` | Se o gerador de configs (LHS) está rodando, e PID |
| `POST` | `/api/generator/start?nConfigs=&seed=` | Inicia `cli/generate.py` (`nConfigs` múltiplo de 3, ≥ 3) |
| `POST` | `/api/generator/stop` | `kill -INT` no gerador |
| `GET` | `/api/prepare/status` | Se o preparo de imagens Docker está rodando, e PID |
| `POST` | `/api/prepare/start?force=` | Inicia `cli/prepare.py` |
| `POST` | `/api/prepare/stop` | `kill -INT` no prepare |
| `GET` | `/api/runner/status` | Se a execução da fila está rodando, e PID |
| `POST` | `/api/runner/start` | Inicia `cli/run.py` (exige fila não vazia) |
| `POST` | `/api/runner/stop` | `kill -INT` no runner (tarefa atual volta a `pending`) |

**Streaming de log (SSE)**:

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/stream/generate` | Log ao vivo de `generate.log` |
| `GET` | `/stream/prepare` | Log ao vivo de `prepare.log` |
| `GET` | `/stream/runner` | Log ao vivo de `runner.log` |

**Fila e resultados** (Postgres de controle, via `JdbcTemplate`):

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/queue` | Lista todas as tarefas da fila (reconcilia `running`→`pending` na resposta se o runner não estiver de fato ativo) |
| `GET` | `/api/results/list` | Lista tarefas com resultado disponível |
| `GET` | `/api/results/{tier}/{combo}/{taskId}` | Resultado completo de uma tarefa (`tasks` + `task_results` achatados) |

**Docker e manutenção**:

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/images/status` | Verifica se as 6 imagens Docker necessárias existem localmente |
| `POST` | `/api/reset` | Trunca fila/resultados, apaga logs e remove containers de benchmark (bloqueado se algo estiver rodando) |

**Hardware**:

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/metrics` | Snapshot atual de métricas de hardware (poll de 1s) |
| `GET` | `/api/server-info` | Informação estática (modelo de CPU, núcleos, RAM total, sensores disponíveis) |

## Decisões de arquitetura

- **Spring MVC (não WebFlux)**: a carga é poucas conexões SSE de longa
  duração + polling de baixa frequência, não alta concorrência; WebFlux só
  compensaria com um driver Postgres reativo (R2DBC), complexidade sem
  benefício nesse tamanho de serviço. Como contrapartida ao modelo de
  thread-por-requisição do MVC, threads virtuais (`spring.threads.virtual.enabled: true`,
  viável só por causa do Java 21) mantêm o custo de concorrência baixo sem
  precisar reescrever tudo em estilo reativo. É o que permite, por
  exemplo, uma virtual thread dedicada por conexão SSE em
  `LogStreamService` sem esgotar um pool.
- **JdbcTemplate (não JPA/Hibernate)**: as colunas JSONB (`config`,
  `result_summary`, `tpc_h`, `tpc_ds`, `hw_metrics`) ficam opacas de
  propósito, já que quem define e evolui o formato interno é a Pipeline
  (Python), não este serviço. Mapear isso para entidades JPA obrigaria o
  Backend a conhecer, e manter sincronizado, um schema que não é dele.
  Na prática, `JsonbUtil.parse(rs, coluna)` lê a coluna como texto e usa
  Jackson (`ObjectMapper.readValue(raw, Object.class)`) só para
  transformar em `Map`/`List`/primitivos Java, de forma que a resposta
  HTTP saia como JSON aninhado de verdade (e não como uma string JSON
  escapada dentro de outro JSON).
- **Connection string em formato libpq, não JDBC**: `DATABASE_URL` chega
  como `postgresql://user:pass@host:port/db`, a mesma variável de ambiente
  e mesmo formato que a Pipeline (Python/psycopg) usa. Como
  `spring.datasource.url` do Spring Boot espera o formato
  `jdbc:postgresql://...`, a autoconfiguração de datasource é
  explicitamente excluída (`DataSourceAutoConfiguration` em
  `application.yml`) e o `DataSource` (HikariCP) é construído manualmente
  em `DataSourceConfig`, usando `DatabaseUrlParser` para separar
  host/porta/banco/usuário/senha a partir da URI libpq. É um ponto que
  falha silenciosamente se alguém "simplificar" tentando usar as
  propriedades padrão do Spring Boot.
- **Métricas de hardware (`/api/metrics`, `/api/server-info`) são uma porta
  nativa** de `monitoring/collector.py` da Pipeline: leem os mesmos
  arquivos de `/proc` e `/sys`, sem shell-out pro Python (endpoint de maior
  frequência do backend, poll de 1s por navegador conectado). Ver seção
  "Como funciona" acima para o detalhe da implementação.
- **`ProcessSupervisor.stop()` manda `kill -INT`, não `Process.destroy()`**
  Isso porque os scripts `cli/*.py` da Pipeline só tratam `SIGINT` especificamente
  (`signal.signal(signal.SIGINT, ...)`); `destroy()` manda `SIGTERM` e
  pularia o encerramento gracioso deles.
- **`docker-java` (mesma lib usada pelo Testcontainers)** para
  `DockerService`: fala HTTP com o daemon Docker via socket Unix
  (`DefaultDockerClientConfig` + `ZerodepDockerHttpClient`), equivalente
  Java de `docker.from_env()` do backend Python original. Escolhida por
  ser madura, sem dependências nativas extras e já testada em larga escala
  pela comunidade Testcontainers.
- **Lock por recurso em `ProcessSupervisor` (`ReentrantLock` por `ManagedProcessKind`)**
  Existe para cobrir uma race condition que o backend Python original
  não tinha: como o Spring MVC atende requisições em múltiplas threads
  (diferente do FastAPI original, efetivamente single-threaded via
  `asyncio` para essa lógica), duas chamadas quase simultâneas ao mesmo
  endpoint de "start" poderiam, sem o lock, passar as duas pela checagem
  de "já está rodando?" antes de qualquer uma marcar o processo como
  iniciado.

## Validação e resultados

Este repositório não produz resultado de pesquisa: ele não roda
benchmark, não treina modelo, não gera métrica de ML. Os resultados
finais do meta-modelo (LHS + XGBoost/XGBRanker) são responsabilidade
exclusiva da [Pipeline](../Autotuning-PostgreSQL-Pipeline) e estão
documentados lá.

O "resultado" deste repositório é a **paridade funcional comprovada** com
o backend Python/FastAPI que ele substitui. Como esta é uma reescrita de
stack (não uma reescrita de comportamento), a validação central foi
garantir que nada mudou do ponto de vista de quem consome a API:

- **Verificação endpoint por endpoint** contra o comportamento do backend
  original: mesma rota, mesmo verbo HTTP, mesmo formato de payload de
  entrada/saída, mesmos códigos de status em caso de conflito (por
  exemplo, tentar iniciar o runner com o gerador ainda rodando retorna
  `409` nos dois backends).
- **Testado com a stack real**, não só com mocks: Postgres de controle
  real rodando via Docker, subprocessos Python reais da Pipeline sendo
  efetivamente iniciados/parados pelo `ProcessSupervisor`, containers de
  benchmark reais sendo criados/removidos via `DockerService`.
- **Teste end-to-end com o Frontend**, usando Playwright para dirigir a
  aplicação completa (Frontend → Backend → Pipeline → Postgres) e
  confirmar que um fluxo real de uso (gerar configurações, preparar
  imagens, rodar a fila, acompanhar logs via SSE, visualizar resultados)
  funciona de ponta a ponta produzindo dado real, não fixture.

Não há suíte de testes automatizados versionada neste repositório
(`src/test/java` está vazio) e, portanto, não há números de cobertura ou
relatório de execução para citar aqui. A validação descrita acima foi
exploratória/manual, verificada em 09/08/2026. Quem for reaproveitar
este código deve tratar essa lacuna como uma limitação prática, não fingir
que existe uma rede de segurança de testes que não existe.

## Limitações

- **Assume Pipeline e Backend na mesma máquina.** A orquestração é feita
  via `ProcessBuilder` (subprocesso local) e leitura direta de arquivos de
  log em `PIPELINE_ROOT`. Não existe nenhum protocolo de rede entre
  Backend e Pipeline. Isso não é uma arquitetura distribuída de verdade:
  rodar os dois em máquinas diferentes exigiria reescrever essa camada
  inteira (por exemplo, para SSH remoto, um agente próprio, ou uma fila de
  mensagens), o que nunca foi feito nem foi objetivo do projeto.
- **CORS aberto e zero autenticação.** `CorsConfig` libera
  `allowedOriginPatterns("*")` para qualquer origem, método e header, e
  nenhum endpoint exige autenticação ou autorização. Isso é adequado
  apenas para desenvolvimento local ou ambiente de confiança total. Expor
  este backend a uma rede não confiável sem colocar autenticação e
  restringir CORS na frente seria um risco de segurança real (qualquer
  site aberto no mesmo navegador poderia chamar `/api/reset` ou iniciar
  processos arbitrários na máquina).
- **Lock de concorrência é em memória do processo.** O `ReentrantLock` por
  `ManagedProcessKind` em `ProcessSupervisor` só protege contra corrida
  dentro da mesma instância da JVM. Não escala para múltiplas réplicas do
  Backend atrás de um load balancer, porque cada instância teria seu
  próprio mapa de locks e processos, sem visibilidade sobre o que a outra
  instância já iniciou. O desenho pressupõe uma única instância de
  Backend por Pipeline, o que é consistente com a limitação anterior
  (mesma máquina), mas vale deixar explícito.
- **`DockerImageTags.REQUIRED` é uma lista hardcoded**, espelhando
  manualmente as tags de imagem que `benchmarks/image_builder.py` (na
  Pipeline) realmente usa. O próprio código documenta que essa é a única
  fonte de verdade real, já que `specs/docker.json` na Pipeline só guarda
  limites de recurso por tier, não as tags. Se a Pipeline adicionar,
  remover ou renomear um tier/imagem, essa lista no Backend precisa ser
  atualizada manualmente em paralelo, um risco de dessincronia silenciosa
  que já existia (de forma equivalente) no backend Python original, então
  não é uma regressão introduzida pela reescrita, mas também não foi
  corrigido nela.

## Estrutura do Projeto

| Diretório / Arquivo | Descrição |
|---|---|
| `src/main/java/com/autotuning/backend/control/` | Controllers REST (Queue, Results, Generator, Prepare, Runner, Images, Metrics, ServerInfo, Reset) |
| `src/main/java/com/autotuning/backend/stream/` | `LogStreamController` e `LogStreamService`, streaming de log via SSE |
| `src/main/java/com/autotuning/backend/process/` | `ProcessSupervisor`, sobe e derruba os subprocessos Python da Pipeline |
| `src/main/java/com/autotuning/backend/hw/` | `HardwareInfoService`, leitura nativa de métricas de hardware |
| `src/main/java/com/autotuning/backend/docker/` | `DockerService`, integração com o Docker Engine via docker-java |
| `src/main/java/com/autotuning/backend/queue/` | `TaskDao`, acesso à fila de tarefas no Postgres |
| `src/main/java/com/autotuning/backend/results/` | `ResultsDao`, acesso aos resultados no Postgres |
| `src/main/java/com/autotuning/backend/images/` | Verificação das imagens Docker necessárias |
| `src/main/java/com/autotuning/backend/metrics/` | Modelos e endpoint de métricas de hardware |
| `src/main/java/com/autotuning/backend/serverinfo/` | Informação estática do servidor (CPU, sensores) |
| `src/main/java/com/autotuning/backend/reset/` | Endpoint de reset da fila e dos resultados |
| `src/main/java/com/autotuning/backend/db/` | `DatabaseUrlParser` e configuração do `DataSource` |
| `src/main/java/com/autotuning/backend/config/` | Configurações gerais (CORS, etc.) |
| `src/main/resources/application.yml` | Configuração do Spring Boot |
| `pom.xml` | Definição Maven do projeto e das dependências |

## Requisitos

| Dependência | Versão | Instalação |
|---|---|---|
| Java | 21 | Necessário no PATH para `mvn spring-boot:run` |
| Maven | 3.x | Gerencia build e dependências do projeto |
| Postgres de controle da Pipeline | rodando | `make db-up` no repositório Pipeline, antes de subir o Backend |

## Variáveis de ambiente

| Variável | Padrão | Descrição |
|---|---|---|
| `PIPELINE_ROOT` | `../Autotuning-PostgreSQL-Pipeline` | Raiz do repositório Pipeline (scripts `cli/*.py`, logs) |
| `DATABASE_URL` | `postgresql://autotuning:autotuning@localhost:5433/autotuning_queue` | Connection string (formato libpq) do Postgres de controle |
| `PORT` | `8000` | Porta HTTP |

## Como Executar

```bash
mvn spring-boot:run
```

Sobe em `http://localhost:8000` por padrão.

---

> Documentacao gerada com auxilio de IA.
