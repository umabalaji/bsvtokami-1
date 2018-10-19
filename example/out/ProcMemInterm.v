Require Import Bool String List Arith.
Require Import Omega.
Require Import micromega.Lia.
Require Import Kami.
Require Import Lib.Indexer.
Require Import Bsvtokami.

Require Import FunctionalExtensionality.

Set Implicit Arguments.


Require Import FIFO.
Require Import RegFile.
Require Import ProcMemSpec.
Require Import PipelinedProc.
Require Import ProcDecExec.
Module module'mkProcInterm.
    Section Section'mkProcInterm.
    Variable instancePrefix: string.
    Variable pgm: RegFile.
    Variable dec: Decoder.
    Variable exec: Executer.
    Variable mem: Memory.
    Variable toHost: ToHost.
        (* method bindings *)
    (* method binding *) Let pc := mkReg (Bit PgmSz) (instancePrefix--"pc") ($0)%bk.
    (* method binding *) Let rf := mkProcRegs (instancePrefix--"rf").
    (* method binding *) Let sb := mkScoreboard (instancePrefix--"sb").
    (* method binding *) Let e2wFifo := mkFIFO E2W (instancePrefix--"e2wFifo").
    (* method binding *) Let decexec := mkDecExec (instancePrefix--"decexec") (pgm)%bk (dec)%bk (exec)%bk (sb)%bk (e2wFifo)%bk (rf)%bk (mem)%bk (toHost)%bk.
    (* method binding *) Let writeback := mkPipelinedWriteback (instancePrefix--"writeback") (e2wFifo)%bk (sb)%bk (rf)%bk.
    Definition mkProcIntermModule: Modules :=
         (BKMODULE {
        (BKMod (Reg'modules pc :: nil))
    with (BKMod (ProcRegs'modules rf :: nil))
    with (BKMod (Scoreboard'modules sb :: nil))
    with (BKMod (FIFO'modules e2wFifo :: nil))
    with (BKMod (Empty'modules decexec :: nil))
    with (BKMod (Empty'modules writeback :: nil))
    }). (* mkProcInterm *)

(* Module mkProcInterm type RegFile#(Bit#(PgmSz), Bit#(InstrSz)) -> Decoder -> Executer -> Memory -> ToHost -> Module#(Empty) return type Decoder *)
    Definition mkProcInterm := Build_Empty mkProcIntermModule%kami.
    End Section'mkProcInterm.
End module'mkProcInterm.

Definition mkProcInterm := module'mkProcInterm.mkProcInterm.
Hint Unfold mkProcInterm : ModuleDefs.
Hint Unfold module'mkProcInterm.mkProcInterm : ModuleDefs.
Hint Unfold module'mkProcInterm.mkProcIntermModule : ModuleDefs.
