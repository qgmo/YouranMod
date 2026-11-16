#!/usr/bin/env python3
"""给激光对准控制程序的镜像补校验和：最后一字节使全部 8192 字节相加（取低 8 位）为 0，同 IBM PC 的 ROM。

用法：nasm -f bin -o laser_align.bin laser_align.asm && python3 laser_align_sum.py laser_align.bin
生成的镜像放在 src/main/resources/assets/youranmod/rom/laser_align.bin（对准台按它的字节数与 SHA-256 认 EPROM）。
"""
import hashlib
import sys

path = sys.argv[1]
data = bytearray(open(path, 'rb').read())
assert len(data) == 8192, len(data)
data[-1] = 0
data[-1] = (-sum(data)) & 0xFF
assert sum(data) & 0xFF == 0
open(path, 'wb').write(data)
print(len(data), hashlib.sha256(data).hexdigest())
