# Annotating ROMs the way we annotate genomes: notes

An idea to come back to: describe what's in a ROM with the file formats
bioinformatics uses for genomes, and find subroutines in one game that
resemble those in another the way BLAST finds similar genes. Nothing here
is built yet. It extends "Code lenses" in `TODO.md`, and in particular
"Annotation by homology".

The analogy is closer than it first looks:

| Genomes | ROMs |
|---|---|
| a genome | a ROM (its PRG banks as contigs) |
| an accession number | the ROM's SHA-1 (No-Intro and NesCartDB catalogue them this way) |
| a gene | a subroutine |
| a protein (what a gene "says") | its instructions, with the addresses taken out |
| gene prediction from start codons | following code from the reset, NMI and IRQ vectors |
| RNA-seq evidence that a gene is used | a code/data log of what ran while someone played |
| a reading frame | where the instructions start (they are 1 to 3 bytes long) |
| a housekeeping gene (16S) | the controller-read loop, the PPU warm-up wait at reset |
| a protein family (Pfam) | a routine shared across many games, as a profile |
| liftover between assemblies | ROM offset to CPU address, through the mapper's banks |

## What exists already

**Annotation files.** Every emulator and disassembler has its own format,
and none is a standard:

- Mesen label files (`.mlb`): `P:1A2B:read_pad:comment` per line, where
  `P` is an offset into PRG ROM and `R` an address in RAM.
- FCEUX name lists (`.nl`): one file per bank, `$C000#Name#Comment`.
- ca65's debug file (`.dbg`): the assembler's spans, segments, symbols
  and source lines. The closest thing to a feature table.
- 6502bench SourceGen projects: JSON with labels, comments and how to
  show each offset.
- Data Crystal's ROM maps: written by hand on a wiki, a little like a
  curated GenBank.

**Code/data logs (CDL).** One flag byte per ROM byte: ran as code, read
as data, drawn as a tile. A track with a value per position, like a
coverage track or the quality line of a FASTQ record. The Code tab here
already records one and downloads it in FCEUX's `.cdl` format.

**"BLAST for binaries."** Reverse engineering has this for x86 and ARM:

- IDA's FLIRT: byte patterns with wildcards where relocated addresses go,
  to recognize library functions. Close to a motif or a PSSM.
- Ghidra's Function ID: hashes of function bodies with the operands
  masked out. Exact matches only.
- Ghidra's BSim: similarity search over features, with locality-sensitive
  hashing. Close to Mash.
- ssdeep and TLSH: fuzzy hashes of whole files.
- Opcode n-grams (k-mers of instructions): a standard feature for
  classifying malware.

None of it is aimed at the 6502 that I know of, and none of it uses the
bioinformatics formats.

## The formats, one at a time

### FASTA: two ways to spell a ROM

A byte has 256 values, and sequence tools expect 4 or about 20 letters.
Two encodings, for two kinds of similarity:

- **As DNA: raw bytes.** Two bits per nucleotide, so a byte is exactly 4
  of them (`00 = A, 01 = C, 10 = G, 11 = T`). The ROM is then a real DNA
  sequence and every tool works on it: `mash`, `minimap2`, `blastn`. This
  finds identical bytes: copied data and tiles, or a sound driver that
  sits at the same addresses in both games. Code that has moved won't
  match, because its addresses change.
- **As protein: instructions.** Disassemble, drop the operands, keep the
  opcode. The 151 opcodes come down to 56 mnemonics, and those to about
  15 families (loads, stores, compares, branches, shifts, stack, flags,
  ...), which fits in the 20 amino-acid letters. This is the level where
  a routine that has moved, or been edited a little, still matches: the
  real "BLAST for subroutines".
  - Choose the letters so that families BLOSUM62 already counts as
    similar are families we'd like counted as similar (branches as
    I/L/V/M, say). Then stock `blastp`, `mmseqs` and `mash sketch -a`
    behave about right.
  - Better: our own substitution matrix. SSEARCH (from the FASTA package)
    and parasail accept one.
  - The operands needn't all go. Keep a class for each: hardware register,
    zero page, RAM, ROM, immediate. `LDA $2002` (the PPU's status) and
    `LDA $0300` have nothing in common, and the hardware registers a
    routine touches probably say the most about what it does.

### GFF3: the annotation itself

The best fit. One line per feature:

```
##gff-version 3
##sequence-region star-run.prg0 1 16384
star-run.prg0  gamedev-toolkit  subroutine     1234  1301  .  .  .  ID=solid-at;Name=solid-at
star-run.prg0  gamedev-toolkit  loop           1250  1290  .  .  .  Parent=solid-at
star-run.prg0  gamedev-toolkit  pointer_table  4000  4031  .  .  .  ID=level-ids
```

- `seqid`: the ROM by name or SHA-1, or one per PRG bank.
- `start` and `end`: offsets into the ROM. GFF3 counts from 1 and
  includes the end, so mind the off-by-one.
- `type`: our own small vocabulary, in place of the Sequence Ontology:
  `subroutine`, `nmi_handler`, `reset_handler`, `pointer_table`,
  `level_data`, `chr_tile`, `music_data`, ...
- `strand` and `phase`: always `.`. (Phase means "bases to skip to the
  next codon", which doesn't work for instructions of different lengths.)
- Attributes: `ID`, `Name`, `Parent` (a loop in a subroutine, a
  subroutine in a bank), `Note`, `Dbxref` (to Data Crystal, say).
- A `##FASTA` section can carry the ROM's bytes in the same file. Include
  it for our own games, never for commercial ones: annotations that name
  a ROM by its hash can be shared without the ROM, which is how `.mlb`
  files get passed around now.
- With a mapper, one offset can appear at several CPU addresses, and one
  address can show different banks. A coordinate liftover, like a UCSC
  chain file.

### GenBank

Also fits. `LOCUS` and `SOURCE` give the ROM's name, hash and mapper;
`FEATURES` the subroutines and tables; and a code feature's
`/translation=` qualifier can carry its instructions as protein letters,
the way a CDS carries its protein. One snag: parsers strip digits from the
`ORIGIN` block, so hex won't survive there. The DNA spelling does.

### Alignments

- **SAM:** at the DNA level, "where does this routine land in that ROM".
- **Stockholm and HMMER profiles:** the interesting one. A few routines
  are in nearly every NES game: the controller read (write `$4016`, read
  it 8 times), the wait for the PPU to warm up at reset (`BIT $2002 /
  BPL`, twice), and the sprite DMA write in the NMI handler. A multiple
  alignment of the controller read across a few hundred games would be
  the first protein family, and a good benchmark for everything else.

### k-mers and Mash

Sketch every ROM's instructions with `mash sketch -a` (or sourmash's
protein sketches), and `mash dist` gives a distance between any two ROMs.
A guess worth testing: games cluster by developer and by shared engine or
sound driver. Cheap to try once the protein spelling exists.

## Where the analogy strains

- **Code and data are mixed.** Disassembled data is junk protein. Gene
  calling has to come first: follow code statically from the vectors,
  add what a code/data log saw run, and trust what both agree on. (The
  experiment in `TODO.md`'s Code lenses section found far more routines
  by playing than statically.)
- **Reading frames.** Start disassembling one byte off and you get
  different instructions, a frameshift. On the 6502 it usually falls back
  into step after a few instructions, which is a useful thing to measure.
- **Jump tables, data after a `JSR`, and bank switching** defeat static
  analysis, as they do in the Code tab already.
- **Operands.** Masking them all throws away the most telling part; the
  operand classes above are the middle ground to try.

## Where to start

Our own games have something reverse engineering never has: the true
answer. The linker knows exactly where every subroutine starts and ends.

1. **Write a GFF3 file from every build**, and a Mesen `.mlb` beside it
   (useful for debugging in Mesen anyway). The symbol table and the
   linker already have what's needed. About an afternoon.
2. **A controlled experiment.** `solid-at`, `probe`, the NMI handler and
   the controller read are in Star Hopper, Star Tower and Star Run, at
   different addresses and with small differences. Spell each ROM as
   protein, `blastp` our known routines against the other games, and
   check that the hits are exactly the spans the GFF3 files give. Another
   afternoon or so, with a table from mnemonics to letters.
3. **Then other ROMs** you own: the same pipeline, sharing only the
   annotations and sketches, never the ROMs.
