from pathlib import Path
p=Path(r'D:\praveen\PAS\app\src\main\java\com\pnkastro\pas\MainActivity.kt')
text=p.read_text(encoding='utf-8')
lines=text.splitlines()
# total counts
open_count=text.count('{')
close_count=text.count('}')
print('TOTAL { =', open_count, ' } =', close_count)
# find cumulative imbalance and where it's max
cum=0
maxcum=0
maxline=0
for i,l in enumerate(lines):
    cum += l.count('{') - l.count('}')
    if cum>maxcum:
        maxcum=cum
        maxline=i
print('MAX CUM at line', maxline+1, 'value=', maxcum)
# print 10 lines around that line
start=max(0, maxline-5)
end=min(len(lines), maxline+6)
print('\nContext around max imbalance (lines {}-{}):\n'.format(start+1,end))
for idx in range(start,end):
    print(f'{idx+1:4}: {lines[idx]}')
# also show last 50 lines to inspect file end
print('\n--- FILE END (last 50 lines) ---')
for idx in range(max(0,len(lines)-50), len(lines)):
    print(f'{idx+1:4}: {lines[idx]}')

