import numpy as np, math, sys
# Checks the paint-height scenario output in DIR (default out_wgpu_ph_vk) against this independent
# NumPy implementation of the shader math.
D=sys.argv[1] if len(sys.argv)>1 else "out_wgpu_ph_vk"
W,H=197,143
seed=np.frombuffer(open(D+"/ph_seed.raw","rb").read(),np.uint8).reshape(H,W,4).astype(np.float64)/255
tooth=np.frombuffer(open(D+"/ph_tooth.raw","rb").read(),np.uint8).reshape(31,29).astype(np.float64)/255
ph=np.frombuffer(open(D+"/ph_height.raw","rb").read(),np.float32).reshape(H,W)
dabs=np.frombuffer(open(D+"/ph_dabs.raw","rb").read(),np.float32).reshape(-1,16)
out=np.frombuffer(open(D+"/ph_out.raw","rb").read(),np.uint8).reshape(H,W,4)
f32=np.float32
base,hs,ts,ox,oy=0.2,0.7,1.7,-13.3,5.2
def cov(t,h):
    if t<=h: return 1.0
    if t>=1: return 0.0
    if h>=0.999: return 1-(t-h)/0.001
    return 1-(t-h)/(1-h)
ref=seed.copy()
for y in range(H):
  for x in range(W):
    px,py=x+.5,y+.5; best=0; brgb=None
    for d in dabs:
      cx,cy,r,a=d[0],d[1],max(d[2],.5),d[3]
      if abs(px-cx)>r or abs(py-cy)>r: continue
      c=cov(math.hypot(px-cx,py-cy)/r, d[11])
      if c<=0: continue
      cellx=math.floor(f32(px)/f32(ts)+f32(ox)); celly=math.floor(f32(py)/f32(ts)+f32(oy))
      tv=tooth[celly%31][cellx%29]
      sh=min(max(base+tv*hs,0),1); lp=max(ph[y][x],0)
      bar=min(max(sh-lp,0),1); cd=min(max(d[12],0),1); resp=min(max(d[15],0),1)
      gate=min(max((1-resp)+(1 if cd>=bar else 0)*resp,0),1)
      c*=min(max(d[13],0),1)*min(max(d[14],0),1)*gate
      if c<=0: continue
      sa=d[8]*a*max(d[9],0)*c
      if sa>best: best=sa; brgb=d[5:8]
    if best>0:
      p=ref[y][x]; rgb=brgb*best+p[:3]*(1-best); al=best+p[3]*(1-best)
      ref[y][x]=np.round(np.concatenate([rgb,[al]])*255)/255
refb=np.round(ref*255).astype(int)
d=np.abs(refb-out.astype(int))
print(D+" vs python ref: differ",(d>0).sum(),"max",d.max())
