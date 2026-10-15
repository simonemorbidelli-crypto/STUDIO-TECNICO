#!/usr/bin/env python3
"""Arricchisce app/src/main/assets/mappa/legende.js (GEOS.V) con le regole del Geoscopio lette dai
preset Tolomeo (ToloExtParamsJSServlet?paramPreset=Legenda...):
  nodo:   x=1  categoria "mutualExclusive" (un solo strato acceso fra i suoi strati)
          xc=1 categoria "mutualExclusiveCategories" (una sola sottocategoria accesa)
  strato: q=1  interrogabile (autoLayer.identifyAllowed)
          st   stile WMS (defaultStyle) se diverso da vuoto/default
  nodo:   i    descrizione (toolTip) della categoria
Uso: arricchisci_legende.py <cartella_preset> <map.txt> <legende.js>   (idempotente)"""
import json,sys
cart,mapf,leg=sys.argv[1:4]
s=open(leg,encoding='utf-8').read()
i=s.index('=')+1
pre=s[:i]; o,fine=json.JSONDecoder().raw_decode(s[i:].lstrip()); resto=s[i:].lstrip()[fine:]
pages=dict(l.split() for l in open(mapf))
n={'x':0,'xc':0,'q':0,'st':0,'i':0}
for v in o['V']:
    p=json.load(open(f'{cart}/preset_{pages[v["id"]+".html"]}.json'))
    cats=[c for mp in p['mappe']['mappaList'] if mp.get('legenda') for c in mp['legenda']['categoryList']]
    def w(vt,pc):
        assert len(vt)==len(pc)
        for nd,c in zip(vt,pc):
            assert nd['n']==c['name']
            for k,f in (('x','mutualExclusive'),('xc','mutualExclusiveCategories')):
                nd.pop(k,None)
                if c.get(f): nd[k]=1; n[k]+=1
            nd.pop('i',None)
            if c.get('toolTip'): nd['i']=c['toolTip']; n['i']+=1
            lv=nd.get('l',[]); lp=c.get('layerList',[])
            assert len(lv)==len(lp)
            for a,b in zip(lv,lp):
                a.pop('q',None);a.pop('st',None)
                au=b.get('autoLayer')
                if au and au.get('identifyAllowed'): a['q']=1;n['q']+=1
                st=b.get('defaultStyle') or ''
                if st not in ('','default') and a.get('w'): a['st']=st;n['st']+=1
            w(nd.get('c',[]),c.get('categoryList',[]))
    w(v['t'],cats)
open(leg,'w',encoding='utf-8').write(pre+json.dumps(o,ensure_ascii=False,separators=(',',':'))+resto)
print(n)
