#!/usr/bin/env python3
"""
Clears the executable-stack flag (PF_X) on an ELF file's PT_GNU_STACK
program header segment, in place — equivalent to `execstack -c <file>`,
for environments where the execstack tool itself isn't packaged
(Termux's binutils doesn't appear to include it).
"""
import struct
import sys

PT_GNU_STACK = 0x6474e551
PF_X = 0x1

def clear_execstack(path):
    with open(path, 'r+b') as f:
        data = bytearray(f.read())

        if data[:4] != b'\x7fELF':
            raise ValueError("Not an ELF file")

        ei_class = data[4]  # 1 = 32-bit, 2 = 64-bit
        is64 = (ei_class == 2)
        endian = '<' if data[5] == 1 else '>'  # 1 = little-endian

        if is64:
            e_phoff = struct.unpack_from(endian + 'Q', data, 0x20)[0]
            e_phentsize = struct.unpack_from(endian + 'H', data, 0x36)[0]
            e_phnum = struct.unpack_from(endian + 'H', data, 0x38)[0]
        else:
            e_phoff = struct.unpack_from(endian + 'I', data, 0x1C)[0]
            e_phentsize = struct.unpack_from(endian + 'H', data, 0x2A)[0]
            e_phnum = struct.unpack_from(endian + 'H', data, 0x2C)[0]

        found = False
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            if is64:
                p_type, p_flags = struct.unpack_from(endian + 'II', data, off)
            else:
                p_type = struct.unpack_from(endian + 'I', data, off)[0]
                p_flags = struct.unpack_from(endian + 'I', data, off + 24)[0]

            if p_type == PT_GNU_STACK:
                found = True
                if p_flags & PF_X:
                    new_flags = p_flags & ~PF_X
                    flags_off = off + 4  # p_flags is at offset 4 in both formats
                    struct.pack_into(endian + 'I', data, flags_off, new_flags)
                    print(f"Cleared PF_X: 0x{p_flags:x} -> 0x{new_flags:x}")
                else:
                    print("PF_X already clear, nothing to do")
                break

        if not found:
            print("No PT_GNU_STACK segment found — nothing to patch")
            return False

        f.seek(0)
        f.write(data)
        return True

if __name__ == '__main__':
    if len(sys.argv) != 2:
        print("usage: clear_execstack.py <file>")
        sys.exit(1)
    clear_execstack(sys.argv[1])
