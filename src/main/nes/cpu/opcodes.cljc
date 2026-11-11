(ns nes.cpu.opcodes
  "The 6502's instruction set, as data.

  Each instruction is one opcode byte, sometimes followed by one or two
  bytes of operand. The opcode says both WHAT to do (the mnemonic, like
  LDA = 'load accumulator') and WHERE to find the data (the addressing
  mode). LDA has eight opcodes, one per addressing mode it supports.

  The NES's CPU (a Ricoh 2A03) is a 6502 without decimal mode. This table
  has the 151 official opcodes. The other 105 byte values are
  'illegal' opcodes, which a few games use; we leave them out for now.

  See https://www.nesdev.org/wiki/CPU_unofficial_opcodes for the full map
  and https://www.masswerk.at/6502/6502_instruction_set.html for details.")

(def modes
  "Addressing modes: how an instruction finds its operand.
  `:size` is the whole instruction's length in bytes."
  {:imp {:size 1 :name "Implied"          :example "CLC"}
   :acc {:size 1 :name "Accumulator"      :example "ASL A"}
   :imm {:size 2 :name "Immediate"        :example "LDA #$01"}
   :zp  {:size 2 :name "Zero page"        :example "LDA $10"}
   :zpx {:size 2 :name "Zero page,X"      :example "LDA $10,X"}
   :zpy {:size 2 :name "Zero page,Y"      :example "LDX $10,Y"}
   :abs {:size 3 :name "Absolute"         :example "LDA $1234"}
   :abx {:size 3 :name "Absolute,X"       :example "LDA $1234,X"}
   :aby {:size 3 :name "Absolute,Y"       :example "LDA $1234,Y"}
   :ind {:size 3 :name "Indirect"         :example "JMP ($1234)"}
   :izx {:size 2 :name "(Indirect,X)"     :example "LDA ($10,X)"}
   :izy {:size 2 :name "(Indirect),Y"     :example "LDA ($10),Y"}
   :rel {:size 2 :name "Relative"         :example "BNE label"}})

(def ^:private alu-modes
  "Most arithmetic/logic instructions support this same set of 8 modes."
  [:imm :zp :zpx :abs :abx :aby :izx :izy])

(def ^:private by-mnemonic
  "mnemonic -> [[mode opcode] ...]"
  {:adc (map vector alu-modes [0x69 0x65 0x75 0x6D 0x7D 0x79 0x61 0x71])
   :and (map vector alu-modes [0x29 0x25 0x35 0x2D 0x3D 0x39 0x21 0x31])
   :cmp (map vector alu-modes [0xC9 0xC5 0xD5 0xCD 0xDD 0xD9 0xC1 0xD1])
   :eor (map vector alu-modes [0x49 0x45 0x55 0x4D 0x5D 0x59 0x41 0x51])
   :lda (map vector alu-modes [0xA9 0xA5 0xB5 0xAD 0xBD 0xB9 0xA1 0xB1])
   :ora (map vector alu-modes [0x09 0x05 0x15 0x0D 0x1D 0x19 0x01 0x11])
   :sbc (map vector alu-modes [0xE9 0xE5 0xF5 0xED 0xFD 0xF9 0xE1 0xF1])
   :sta [[:zp 0x85] [:zpx 0x95] [:abs 0x8D] [:abx 0x9D] [:aby 0x99] [:izx 0x81] [:izy 0x91]]

   :asl [[:acc 0x0A] [:zp 0x06] [:zpx 0x16] [:abs 0x0E] [:abx 0x1E]]
   :lsr [[:acc 0x4A] [:zp 0x46] [:zpx 0x56] [:abs 0x4E] [:abx 0x5E]]
   :rol [[:acc 0x2A] [:zp 0x26] [:zpx 0x36] [:abs 0x2E] [:abx 0x3E]]
   :ror [[:acc 0x6A] [:zp 0x66] [:zpx 0x76] [:abs 0x6E] [:abx 0x7E]]
   :dec [[:zp 0xC6] [:zpx 0xD6] [:abs 0xCE] [:abx 0xDE]]
   :inc [[:zp 0xE6] [:zpx 0xF6] [:abs 0xEE] [:abx 0xFE]]

   :ldx [[:imm 0xA2] [:zp 0xA6] [:zpy 0xB6] [:abs 0xAE] [:aby 0xBE]]
   :ldy [[:imm 0xA0] [:zp 0xA4] [:zpx 0xB4] [:abs 0xAC] [:abx 0xBC]]
   :stx [[:zp 0x86] [:zpy 0x96] [:abs 0x8E]]
   :sty [[:zp 0x84] [:zpx 0x94] [:abs 0x8C]]
   :cpx [[:imm 0xE0] [:zp 0xE4] [:abs 0xEC]]
   :cpy [[:imm 0xC0] [:zp 0xC4] [:abs 0xCC]]
   :bit [[:zp 0x24] [:abs 0x2C]]

   :bcc [[:rel 0x90]] :bcs [[:rel 0xB0]] :beq [[:rel 0xF0]] :bmi [[:rel 0x30]]
   :bne [[:rel 0xD0]] :bpl [[:rel 0x10]] :bvc [[:rel 0x50]] :bvs [[:rel 0x70]]

   :jmp [[:abs 0x4C] [:ind 0x6C]]
   :jsr [[:abs 0x20]]
   :rts [[:imp 0x60]] :rti [[:imp 0x40]] :brk [[:imp 0x00]]

   :clc [[:imp 0x18]] :cld [[:imp 0xD8]] :cli [[:imp 0x58]] :clv [[:imp 0xB8]]
   :sec [[:imp 0x38]] :sed [[:imp 0xF8]] :sei [[:imp 0x78]]
   :dex [[:imp 0xCA]] :dey [[:imp 0x88]] :inx [[:imp 0xE8]] :iny [[:imp 0xC8]]
   :tax [[:imp 0xAA]] :tay [[:imp 0xA8]] :tsx [[:imp 0xBA]]
   :txa [[:imp 0x8A]] :txs [[:imp 0x9A]] :tya [[:imp 0x98]]
   :pha [[:imp 0x48]] :php [[:imp 0x08]] :pla [[:imp 0x68]] :plp [[:imp 0x28]]
   :nop [[:imp 0xEA]]})

(def opcodes
  "opcode byte -> {:mnemonic :lda, :mode :imm, :size 2}"
  (into {}
        (for [[mnemonic entries] by-mnemonic
              [mode opcode] entries]
          [opcode {:mnemonic mnemonic :mode mode :size (get-in modes [mode :size])}])))

(def opcode-for
  "[mnemonic mode] -> opcode byte. The reverse of `opcodes`, for assembling."
  (into {} (for [[op {:keys [mnemonic mode]}] opcodes] [[mnemonic mode] op])))

;; ---------------------------------------------------------------------------
;; Unofficial opcodes
;;
;; The other 105 byte values weren't documented by the 6502's makers, but
;; they still do something: the chip's decoding logic combines parts of
;; neighbouring instructions. Most are combinations of two official
;; instructions (DCP = DEC then CMP; LAX = LDA and LDX at once) or NOPs
;; with extra addressing modes. A few games and many demos use them. 12 of
;; them (the 'JAM' or 'KIL' opcodes) lock the CPU up; they aren't here.
;;
;; Names as on https://www.nesdev.org/wiki/CPU_unofficial_opcodes (and in
;; nestest's log); behaviour as jsnes implements it.

(def ^:private unofficial-by-mnemonic
  {:nop (map vector [:imp :imp :imp :imp :imp :imp :imm :imm :imm :imm :imm
                     :abs :abx :abx :abx :abx :abx :abx :zp :zp :zp :zpx :zpx :zpx :zpx :zpx :zpx]
             [0x1A 0x3A 0x5A 0x7A 0xDA 0xFA 0x80 0x82 0x89 0xC2 0xE2
              0x0C 0x1C 0x3C 0x5C 0x7C 0xDC 0xFC 0x04 0x44 0x64 0x14 0x34 0x54 0x74 0xD4 0xF4])
   :sbc [[:imm 0xEB]]
   :alr [[:imm 0x4B]]
   :anc [[:imm 0x0B] [:imm 0x2B]]
   :arr [[:imm 0x6B]]
   :axs [[:imm 0xCB]]
   :lax (map vector [:izx :zp :abs :izy :zpy :aby] [0xA3 0xA7 0xAF 0xB3 0xB7 0xBF])
   :sax (map vector [:izx :zp :abs :zpy] [0x83 0x87 0x8F 0x97])
   :dcp (map vector [:izx :zp :abs :izy :zpx :aby :abx] [0xC3 0xC7 0xCF 0xD3 0xD7 0xDB 0xDF])
   :isb (map vector [:izx :zp :abs :izy :zpx :aby :abx] [0xE3 0xE7 0xEF 0xF3 0xF7 0xFB 0xFF])
   :slo (map vector [:izx :zp :abs :izy :zpx :aby :abx] [0x03 0x07 0x0F 0x13 0x17 0x1B 0x1F])
   :rla (map vector [:izx :zp :abs :izy :zpx :aby :abx] [0x23 0x27 0x2F 0x33 0x37 0x3B 0x3F])
   :sre (map vector [:izx :zp :abs :izy :zpx :aby :abx] [0x43 0x47 0x4F 0x53 0x57 0x5B 0x5F])
   :rra (map vector [:izx :zp :abs :izy :zpx :aby :abx] [0x63 0x67 0x6F 0x73 0x77 0x7B 0x7F])
   :sha [[:izy 0x93] [:aby 0x9F]]
   :tas [[:aby 0x9B]]
   :shy [[:abx 0x9C]]
   :shx [[:aby 0x9E]]
   :las [[:aby 0xBB]]
   :xaa [[:imm 0x8B]]
   :lxa [[:imm 0xAB]]})

(def unofficial-opcodes
  "opcode byte -> {:mnemonic :mode :size :unofficial? true}"
  (into {}
        (for [[mnemonic entries] unofficial-by-mnemonic
              [mode opcode] entries]
          [opcode {:mnemonic mnemonic :mode mode :size (get-in modes [mode :size]) :unofficial? true}])))

(def all-opcodes
  "Official and unofficial opcodes together: 244 of the 256 bytes."
  (merge opcodes unofficial-opcodes))
