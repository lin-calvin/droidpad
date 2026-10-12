#!/usr/bin/env python3
"""Minimal HID report-descriptor parser to validate DroidPad's descriptor and
verify report packing. Prints fields with bit offsets and total report size."""
import sys

NAME_TAG = {0:"Main",1:"Global",2:"Local",3:"Reserved"}
MAIN = {0x8:"Input",0x9:"Output",0xB:"Feature",0xA:"Collection",0xC:"EndCollection"}
GLOBAL = {0x0:"UsagePage",0x1:"LogicalMin",0x2:"LogicalMax",0x3:"PhysicalMin",
          0x4:"PhysicalMax",0x5:"UnitExp",0x6:"Unit",0x7:"ReportSize",0x8:"ReportID",
          0x9:"ReportCount",0xA:"Push",0xB:"Pop"}
LOCAL = {0x0:"Usage",0x1:"UsageMin",0x2:"UsageMax"}

def parse(data):
    i=0; g={}; l=[]; stack=[]; bit=0; fields=[]; collections=[]
    while i < len(data):
        b=data[i]; i+=1
        size=b&3; size=4 if size==3 else size
        typ=(b>>2)&3; tag=(b>>4)&0xF
        val=int.from_bytes(data[i:i+size],'little') if size else 0
        i+=size
        if typ==1: # global
            n=GLOBAL.get(tag,f"G{tag}")
            if n=="Push": stack.append(dict(g))
            elif n=="Pop": g=stack.pop()
            else: g[n]=val
        elif typ==2: # local
            n=LOCAL.get(tag,f"L{tag}")
            l.append((n,val))
        elif typ==0: # main
            n=MAIN.get(tag,f"M{tag}")
            if n=="Collection":
                collections.append((l[0] if l else None))
            elif n=="EndCollection":
                if collections: collections.pop()
            elif n in ("Input","Output","Feature"):
                rs=g.get("ReportSize",0); rc=g.get("ReportCount",1)
                usages=[u for (t,u) in l if t=="Usage"]
                usage_lo=next((u for (t,u) in l if t=="UsageMin"),None)
                usage_hi=next((u for (t,u) in l if t=="UsageMax"),None)
                rid=g.get("ReportID",0)
                fields.append(dict(kind=n,rid=rid,bit=bit,size=rs,count=rc,
                                   usages=usages,ulo=usage_lo,uhi=usage_hi,
                                   lmin=g.get("LogicalMin"),lmax=g.get("LogicalMax"),
                                   app=[c for c in collections if c],
                                   const=(len(l)==0 and rs and rc and not usages)))
                bit+=rs*rc
            l=[]
    return fields, bit

def main():
    if len(sys.argv)>1:
        data=bytes.fromhex(sys.argv[1])
    else:
        data=bytes.fromhex(sys.stdin.read().replace("\n"," "))
    fields,total=parse(data)
    print(f"descriptor {len(data)} bytes, reports total {total} bits = {total/8:.2f} bytes")
    for f in fields:
        if f['const']: 
            continue
        print(f"  {f['kind']:7} id={f['rid']} bit={f['bit']:4} sz={f['size']} x{f['count']}"
              f" usages={[hex(u) for u in f['usages']]} ulo={f['ulo']} uhi={f['uhi']}"
              f" log={f['lmin']}..{f['lmax']}")
    for rid in sorted({f['rid'] for f in fields}):
        bits=[f for f in fields if f['rid']==rid]
        end=max((f['bit']+f['size']*f['count']) for f in bits)
        print(f"report id {rid}: {end} bits = {end/8:.2f} bytes")

if __name__=="__main__":
    main()
