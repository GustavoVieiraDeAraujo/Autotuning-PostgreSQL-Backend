package com.autotuning.backend.process;

/** Os 3 processos de longa duracao que o backend orquestra na Pipeline (Python). */
public enum ManagedProcessKind {
    GENERATOR,
    PREPARE,
    RUNNER
}
