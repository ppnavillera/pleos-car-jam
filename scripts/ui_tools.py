#!/usr/bin/env python3
"""uiautomator dump(xml) 읽기 도구 (check.sh / e2e 스크립트용).
  ui_tools.py center  <xml> <content-desc>            → "x y" (없으면 종료 코드 1)
  ui_tools.py attr    <xml> <content-desc> <attr>     → 속성 값 (checked 등)
  ui_tools.py has     <xml> <content-desc>            → 있으면 0
  ui_tools.py text    <xml> <content-desc>            → 그 노드 text
  ui_tools.py count   <xml> <content-desc 접두사>      → 접두사로 시작하는 노드 수
  ui_tools.py overlap <xml> x1 y1 x2 y2 <desc>...     → 각 노드 bounds 와 사각형의 교집합 넓이. 겹치면 종료 코드 1
"""
import re, sys

def nodes(xml):
    for m in re.finditer(r'<node ([^>]*?)/?>', xml):
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
        b = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a.get('bounds', ''))
        a['_b'] = tuple(map(int, b.groups())) if b else None
        yield a

def find(xml, desc):
    return [n for n in nodes(xml) if n.get('content-desc') == desc]

def main():
    cmd, path = sys.argv[1], sys.argv[2]
    xml = open(path, encoding='utf-8').read()
    if cmd == 'center':
        f = find(xml, sys.argv[3]); b = f[0]['_b'] if f else None
        if not b: sys.exit(1)
        print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
    elif cmd == 'attr':
        f = find(xml, sys.argv[3])
        if not f: sys.exit(1)
        print(f[0].get(sys.argv[4], ''))
    elif cmd == 'has':
        sys.exit(0 if find(xml, sys.argv[3]) else 1)
    elif cmd == 'text':
        f = find(xml, sys.argv[3])
        if not f: sys.exit(1)
        print(f[0].get('text', ''))
    elif cmd == 'count':
        print(sum(1 for n in nodes(xml) if n.get('content-desc', '').startswith(sys.argv[3])))
    elif cmd == 'overlap':
        x1, y1, x2, y2 = map(int, sys.argv[3:7]); bad = 0
        for d in sys.argv[7:]:
            f = find(xml, d)
            if not f: print(f'MISSING {d}'); bad = 1; continue
            b = f[0]['_b']
            w = min(x2, b[2]) - max(x1, b[0]); h = min(y2, b[3]) - max(y1, b[1])
            area = max(w, 0) * max(h, 0)
            if area > 0: print(f'OVERLAP {d} {area}px² bounds={b}'); bad = 1
        sys.exit(bad)

main()
