"""Reads words and unigram probabilities from an AOSP/HeliBoard v2xx .dict file."""
import struct, sys

def read_dict(path):
    b = open(path, 'rb').read()
    magic, version, flags, header_size = struct.unpack('>IHHI', b[:12])
    assert magic == 0x9BC13AFE, hex(magic)
    pos = 12
    attrs = {}
    def read_raw_string(p):  # header strings: no code point table
        out = []
        while True:
            c = b[p]
            if c < 0x20:
                if c == 0x1F:
                    return out, p + 1
                out.append((b[p] << 16) | (b[p+1] << 8) | b[p+2]); p += 3
            else:
                out.append(c); p += 1
    while pos < header_size:
        k, pos = read_raw_string(pos)
        v, pos = read_raw_string(pos)
        attrs[''.join(map(chr, k))] = v
    table = attrs.get('codePointTable')
    def read_cp(p):
        c = b[p]
        if c < 0x20:
            if c == 0x1F:
                return None, p + 1
            return (b[p] << 16) | (b[p+1] << 8) | b[p+2], p + 3
        return (table[c - 0x20] if table else c), p + 1
    words = []
    def walk(p, prefix):
        n = b[p]; p += 1
        if n >= 0x80:
            n = ((n & 0x7F) << 8) | b[p]; p += 1
        for _ in range(n):
            fl = b[p]; p += 1
            chars = []
            if fl & 0x20:
                while True:
                    c, p = read_cp(p)
                    if c is None: break
                    chars.append(c)
            else:
                c, p = read_cp(p); chars.append(c)
            word = prefix + ''.join(map(chr, chars))
            prob = None
            if fl & 0x10:
                prob = b[p]; p += 1
            child = None
            t = fl & 0xC0
            base = p
            if t == 0x40: child = base + b[p]; p += 1
            elif t == 0x80: child = base + ((b[p] << 8) | b[p+1]); p += 2
            elif t == 0xC0: child = base + ((b[p] << 16) | (b[p+1] << 8) | b[p+2]); p += 3
            if fl & 0x08:
                size = (b[p] << 8) | b[p+1]
                p += size
            if fl & 0x04:
                while True:
                    bf = b[p]; p += 1
                    p += {0x10: 1, 0x20: 2, 0x30: 3}.get(bf & 0x30, 0)
                    if not bf & 0x80: break
            if prob is not None and not fl & 0x02:
                words.append((word, prob, bool(fl & 0x01)))
            if child is not None:
                walk(child, word)
    sys.setrecursionlimit(10000)
    walk(header_size, '')
    return attrs, words

if __name__ == '__main__':
    attrs, words = read_dict(sys.argv[1])
    print({k: (''.join(map(chr, v))[:40]) for k, v in attrs.items() if k != 'codePointTable'})
    print(len(words))
    d = {w: p for w, p, o in words}
    for w in sys.argv[2:]:
        print(w, d.get(w))
    with open(sys.argv[1] + '.tsv', 'w', encoding='utf-8') as f:
        for w, p, o in words:
            f.write(f"{w}\t{p}\n")
