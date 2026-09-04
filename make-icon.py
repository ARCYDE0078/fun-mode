from PIL import Image, ImageDraw
import math, random

SS = 4                      # supersample
OUT = 256
S = OUT * SS

def lerp(a, b, t): return tuple(int(a[i] + (b[i]-a[i])*t) for i in range(3))
GREEN=(87,255,135); YELLOW=(255,214,110); RED=(255,88,69)
def chaos(t):
    return lerp(GREEN, YELLOW, t*2) if t < .5 else lerp(YELLOW, RED, (t-.5)*2)

def panel(draw, dark=(29,28,36)):
    m = int(S*0.02); rad = int(S*0.17)
    draw.rounded_rectangle([m,m,S-m,S-m], radius=rad, fill=dark+(255,))
    return m, rad

def rounded_mask():
    mask = Image.new("L",(S,S),0); md = ImageDraw.Draw(mask)
    m=int(S*0.02); rad=int(S*0.17)
    md.rounded_rectangle([m,m,S-m,S-m], radius=rad, fill=255)
    return mask

def border(draw):
    m=int(S*0.02); rad=int(S*0.17)
    draw.rounded_rectangle([m,m,S-m,S-m], radius=rad, outline=(64,62,78,255), width=int(S*0.014))

# ---------- Variant A: chaos vortex ----------
def variant_a():
    base = Image.new("RGBA",(S,S),(0,0,0,0)); bd=ImageDraw.Draw(base)
    panel(bd)
    content = Image.new("RGBA",(S,S),(0,0,0,0)); d=ImageDraw.Draw(content)
    cx,cy=S/2,S/2
    turns=3.15; th_max=turns*2*math.pi; a=(S*0.345)/th_max
    steps=1600; pts=[]
    for i in range(steps+1):
        t=i/steps; th=t*th_max; r=a*th
        pts.append((cx+r*math.cos(th), cy+r*math.sin(th), t))
    for i in range(len(pts)-1):
        x0,y0,t0=pts[i]; x1,y1,_=pts[i+1]
        w=max(2,int(S*0.006 + S*0.052*t0))
        col=chaos(t0)+(255,)
        d.line([(x0,y0),(x1,y1)],fill=col,width=w)
        d.ellipse([x0-w/2,y0-w/2,x0+w/2,y0+w/2],fill=col)
    # hot core
    for rr,col in [(S*0.085,(255,120,60)),(S*0.058,(255,235,160)),(S*0.03,(255,255,255))]:
        d.ellipse([cx-rr,cy-rr,cx+rr,cy+rr],fill=col+(255,))
    # sparks
    random.seed(7)
    for _ in range(30):
        ang=random.uniform(0,2*math.pi); dist=random.uniform(S*0.14,S*0.44)
        x=cx+dist*math.cos(ang); y=cy+dist*math.sin(ang); rr=random.uniform(S*0.004,S*0.015)
        d.ellipse([x-rr,y-rr,x+rr,y+rr],fill=(255,205,130,230))
    base.paste(content,(0,0),Image.composite(content.split()[3], Image.new("L",(S,S),0), rounded_mask()))
    border(bd_full:=ImageDraw.Draw(base))
    return base

# ---------- Variant B: cursed grin ----------
def variant_b():
    base = Image.new("RGBA",(S,S),(0,0,0,0)); bd=ImageDraw.Draw(base)
    panel(bd, dark=(26,24,32))
    content = Image.new("RGBA",(S,S),(0,0,0,0)); d=ImageDraw.Draw(content)
    cx,cy=S/2,S*0.52
    R=S*0.33
    # wobbly face
    face=[]
    random.seed(3)
    for i in range(90):
        a=i/90*2*math.pi
        rr=R*(1+0.05*math.sin(a*3+0.6)+0.03*math.sin(a*7))
        face.append((cx+rr*math.cos(a), cy+rr*math.sin(a)))
    d.polygon(face, fill=(255,196,74,255))
    # eyes (asymmetric = cursed)
    d.ellipse([cx-R*0.55,cy-R*0.45,cx-R*0.12,cy+R*0.02], fill=(255,255,255,255))
    d.ellipse([cx+R*0.16,cy-R*0.32,cx+R*0.44,cy-R*0.02], fill=(255,255,255,255))
    d.ellipse([cx-R*0.42,cy-R*0.28,cx-R*0.24,cy-R*0.06], fill=(20,18,26,255))   # big pupil
    d.ellipse([cx+R*0.29,cy-R*0.22,cx+R*0.40,cy-R*0.10], fill=(20,18,26,255))   # small pupil
    # jagged wide grin
    y0=cy+R*0.28
    grin=[(cx-R*0.62,y0)]
    n=9
    for i in range(n+1):
        x=cx-R*0.62 + (R*1.24)*(i/n)
        y=y0 + (R*0.36 if i%2 else R*0.10) + R*0.18*math.sin(i)
        grin.append((x,y))
    grin.append((cx+R*0.62,y0))
    d.polygon(grin, fill=(30,20,26,255))
    # teeth line
    d.line([(cx-R*0.6,y0),(cx+R*0.6,y0)], fill=(255,255,255,255), width=int(S*0.02))
    # chaos sparks
    random.seed(11)
    for _ in range(22):
        ang=random.uniform(0,2*math.pi); dist=random.uniform(S*0.30,S*0.45)
        x=cx+dist*math.cos(ang); y=S/2+dist*math.sin(ang)*0.9; rr=random.uniform(S*0.004,S*0.012)
        d.ellipse([x-rr,y-rr,x+rr,y+rr],fill=chaos(random.random())+(230,))
    base.paste(content,(0,0),Image.composite(content.split()[3], Image.new("L",(S,S),0), rounded_mask()))
    border(ImageDraw.Draw(base))
    return base

a=variant_a().resize((OUT,OUT),Image.LANCZOS)
b=variant_b().resize((OUT,OUT),Image.LANCZOS)
a.save("/home/mihail/.claude/jobs/db6016b3/tmp/iconA.png")
b.save("/home/mihail/.claude/jobs/db6016b3/tmp/iconB.png")

# side-by-side preview (larger, on neutral bg)
prev=Image.new("RGBA",(OUT*2+60,OUT+40),(40,40,48,255))
prev.paste(a,(20,20),a); prev.paste(b,(OUT+40,20),b)
prev.save("/home/mihail/.claude/jobs/db6016b3/tmp/preview.png")
print("ok", a.size, b.size)
