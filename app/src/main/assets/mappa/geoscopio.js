/* Strumenti del Geoscopio: legende interattive, cartoteca (download), macchina del tempo,
   selezione di oggetti e stampa PDF. Usa le funzioni della pagina principale (index.html). */
(function(){
var $=function(i){return document.getElementById(i)};
function ok(u){return /^https:\/\//.test(u||"")}
function apri(u){if(!ok(u))return;if(window.Android&&Android.apri)Android.apri(u);else window.open(u,"_blank")}
var S=GEOS.S;

/* ---------- menu "⋯" ---------- */
var menu=$("menu");
function chiudiMenu(){menu.style.display="none"}
$("bAltro").onclick=function(){
 if(menu.style.display=="block"){chiudiMenu();return}
 chiudiTutto();menu.style.display="block"};
menu.querySelectorAll("button").forEach(function(b){b.onclick=function(){chiudiMenu();var f=AZIONI[b.dataset.a];if(f)f()}});
var _ct=chiudiTutto;
chiudiTutto=function(){_ct();menu.style.display="none";$("leg").style.display="none";$("stp").style.display="none"};

/* ---------- legende interattive e cartoteca ---------- */
var leg=$("leg");
var LEG={v:STATO.legV||"cartoteca",f:""};
function titoloLivello(l){return l.d||l.w}
function haLiv(l){return l.w&&l.s!=null&&haExtra(S[l.s],l.w)}
function filtra(nodo,f){ /* true se il nodo contiene qualcosa che corrisponde al filtro */
 if(!f)return true;
 if((nodo.n||"").toLowerCase().indexOf(f)>=0)return true;
 if((nodo.l||[]).some(function(l){return titoloLivello(l).toLowerCase().indexOf(f)>=0}))return true;
 return (nodo.c||[]).some(function(c){return filtra(c,f)})}
var LIVELLI=[];
function htmlNodo(n,dep,f,forza){
 if(!filtra(n,f))return "";
 var h='<details class="cat"'+(f||forza||dep==0?" open":"")+'><summary>'+esc(n.n||"(senza nome)")+'</summary>';
 (n.l||[]).forEach(function(l){
  if(f&&titoloLivello(l).toLowerCase().indexOf(f)<0&&(n.n||"").toLowerCase().indexOf(f)<0)return;
  var k=LIVELLI.length;LIVELLI.push(l);
  h+='<div class="lay">';
  if(l.w&&l.s!=null)h+='<label><input type="checkbox" data-k="'+k+'"'+(haLiv(l)?" checked":"")+'> '+esc(titoloLivello(l))+'</label>';
  else h+=esc(titoloLivello(l));
  if(l.u)h+=' <button class="mini" data-d="'+k+'">📥 Scarica</button>';
  if(l.m)h+=' <button class="mini" data-m="'+k+'">ℹ️ Scheda</button>';
  if(l.i&&l.w)h+='<div class="pic">'+esc(l.i)+'</div>';
  h+='</div>'});
 (n.c||[]).forEach(function(c){h+=htmlNodo(c,dep+1,f,forza)});
 return h+'</details>'}
function disegnaLegenda(){
 var vs=GEOS.V,h='<div class="f"><select id="legV">';
 vs.forEach(function(v){h+='<option value="'+v.id+'"'+(v.id==LEG.v?" selected":"")+'>'+esc(v.n)+'</option>'});
 h+='</select></div><div class="f"><input id="legF" placeholder="Cerca nella legenda" value="'+esc(LEG.f)+'"><button id="legX" title="Spegni tutti i livelli aggiunti">Spegni tutto</button></div>';
 h+='<div class="pic">Spunta i livelli per vederli sulla mappa. Con «📥 Scarica» o toccando un elemento della cartoteca (con «Seleziona» attivo) scarichi i dati.</div>';
 h+='<div id="legT"></div>';
 leg.innerHTML=h;
 $("legV").onchange=function(){LEG.v=this.value;STATO.legV=LEG.v;salva();disegnaAlbero()};
 $("legF").oninput=function(){LEG.f=this.value.trim().toLowerCase();disegnaAlbero()};
 $("legX").onclick=function(){spegniTutto();disegnaExtra();disegnaAlbero();msg("Tutti gli strati sono spenti")};
 disegnaAlbero()}
function disegnaAlbero(){
 var v=GEOS.V.filter(function(x){return x.id==LEG.v})[0]||GEOS.V[0];
 LIVELLI=[];var h="";v.t.forEach(function(c){h+=htmlNodo(c,0,LEG.f,false)});
 var el=$("legT");el.innerHTML=h||'<p class="pic">Nessun elemento corrisponde alla ricerca.</p>';
 el.querySelectorAll("input[data-k]").forEach(function(c){c.onchange=function(){
  var l=LIVELLI[+c.dataset.k],u=S[l.s];
  if(c.checked){aggiungiExtra(u,l.w,titoloLivello(l));
   if(/cartoteca|scarica|download/i.test(l.w+" "+(l.i||"")+" "+l.d)){SEL.on=true;aggiornaSel();msg("Tocca la mappa per selezionare l'elemento da scaricare")}}
  else togliExtra(u,l.w);
  disegnaExtra()}});
 el.querySelectorAll("[data-d]").forEach(function(b){b.onclick=function(){apri(LIVELLI[+b.dataset.d].u)}});
 el.querySelectorAll("[data-m]").forEach(function(b){b.onclick=function(){apri(LIVELLI[+b.dataset.m].m)}})}

/* ---------- selezione di oggetti (cartoteca, strati interrogabili) ---------- */
var SEL={on:false,hl:L.layerGroup().addTo(map)};
window.SEL=SEL;
function aggiornaSel(){var b=menu.querySelector('[data-a=sel]');b.textContent=(SEL.on?"✅":"🎯")+" Seleziona oggetto sulla mappa"}
function pulisciSel(){SEL.hl.clearLayers();$("sel").style.display="none";if(window.marker&&marker){map.removeLayer(marker);marker=null}}
function nomeFile(u){var m=u.split("?")[0].split("/").pop();return m||u}
function riceviInfo(testoHtml){ /* una risposta HTML del Geoscopio -> blocchi {righe, link}, uno per elemento selezionato */
 var d=new DOMParser().parseFromString(testoHtml,"text/html"),blocchi=[];
 d.querySelectorAll("script,style,img").forEach(function(x){x.remove()});
 var tabs=Array.prototype.filter.call(d.querySelectorAll("table"),function(t){return !t.querySelector("table")});
 tabs.forEach(function(t){
  var righe=[],link=[];
  t.querySelectorAll("a[href]").forEach(function(a){var u=a.getAttribute("href");if(ok(u))link.push({u:u,t:(a.textContent||"").replace(/\s+/g," ").trim()||nomeFile(u)})});
  t.querySelectorAll("tr").forEach(function(tr){
   var x=(tr.textContent||"").replace(/\s+/g," ").trim();
   if(!x||/^(Firenze,|Punto selezionato|Coordinate|Distribuzione con Licenza|Strato:)/.test(x))return;
   if(tr.querySelector("a[href]"))return;
   righe.push(x)});
  if(/Punto selezionato/.test(t.textContent||""))return;
  if(righe.length||link.length)blocchi.push({righe:righe,link:link})});
 return blocchi}
function evidenzia(u,lay,bbox){
 var url=u+"&SERVICE=WMS&VERSION=1.3.0&REQUEST=GetFeatureInfo&LAYERS="+encodeURIComponent(lay)+"&QUERY_LAYERS="+encodeURIComponent(lay)+"&STYLES=&CRS=EPSG:3857&BBOX="+bbox.b+"&WIDTH="+bbox.w+"&HEIGHT="+bbox.h+"&I="+bbox.i+"&J="+bbox.j+"&INFO_FORMAT=text/gml&FEATURE_COUNT=1";
 rete("GET",url).then(function(t){
  var srs=(t.match(/srsName="([^"]+)"/)||[])[1]||"";
  var re=/<gml:coordinates[^>]*>([^<]+)<\/gml:coordinates>/g,m,n=0;
  while((m=re.exec(t))&&n<40){
   var pts=m[1].trim().split(/\s+/).map(function(p){var xy=p.split(",").map(Number);
    if(/3857|900913/.test(srs))return L.CRS.EPSG3857.unproject(L.point(xy[0],xy[1]));
    if(/4326/.test(srs)||srs==="")return L.latLng(xy[1],xy[0]);return null}).filter(Boolean);
   if(pts.length>2){L.polygon(pts,{color:"#e07000",weight:3,fillColor:"#ffb347",fillOpacity:.18,interactive:false}).addTo(SEL.hl);n++}}
 }).catch(function(){})}
var REG=/^https:\/\/www502\.regione\.toscana\.it\//;
function stratiAccesi(){ /* tutti gli strati ACCESI e interrogabili: livelli principali + livelli aggiunti (mai quelli spenti) */
 var v=[],visti={},nonInterr=[];
 function aggiungi(o){var k=o.u+"|"+o.l;if(visti[k])return;visti[k]=1;v.push(o)}
 DEF.forEach(function(d){if(!d.on)return;
  if(d.sel)d.sel.forEach(function(x){aggiungi({u:SERVIZI[d.srv],l:x[0],t:x[1],tipo:"plain"})});
  else nonInterr.push(d.nome)});
 EXTRA.forEach(function(e){
  if(/\/MapServer$/.test(e.u))aggiungi({u:e.u,l:e.l,t:e.t,tipo:"agx"});
  else if(REG.test(e.u))aggiungi({u:e.u,l:e.l,t:e.t,tipo:"html"})});
 return {v:v,nonInterr:nonInterr}}
function righeDaPlain(testo,layer){ /* testo/plain di MapServer -> un blocco per elemento */
 var out=[],parti=String(testo).split(/\n\s*Feature \d+:/).slice(1);
 parti.forEach(function(p){
  var v={},ordine=[],m,re=/^\s+([A-Za-z0-9_\.]+) = '(.*)'\s*$/gm;
  while((m=re.exec(p))){if(!(m[1] in v)){v[m[1]]=m[2].trim();ordine.push(m[1])}}
  var righe=[];
  if(layer=="rt_cat.idcatpart.rt"){
   CAMPI.forEach(function(c){var x=v[c[0]];if(x===undefined||x==="")return;
    if(c[0]=="foglio"||c[0]=="particella")x=x.replace(/^0+(?=\d)/,"");
    if(c[0].indexOf("reddito")==0){var n=parseFloat(x);x=isNaN(n)?x:n.toFixed(2)}
    righe.push(c[1]+": "+x)})}
  else ordine.forEach(function(k){var x=v[k];if(!x||CAMPI_OMESSI.test(k)||righe.length>=12)return;if(x.length>90)x=x.slice(0,87)+"…";righe.push(k.replace(/_/g," ")+": "+x)});
  if(righe.length)out.push({righe:righe,link:[]})});
 return out}
function interroga(ev){
 var A=stratiAccesi(),CAP=60,viv=A.v.slice(0,CAP);
 pulisciSel();
 if(!viv.length){msg(A.nonInterr.length?"Gli strati accesi non sono interrogabili ("+A.nonInterr.join(", ")+"): accendine un altro":"Accendi prima un livello (catasto, legenda o WMS)");return}
 if(window.marker===undefined)window.marker=null;
 marker=L.circleMarker(ev.latlng,{radius:7,color:"#c00",weight:3,fillOpacity:.2}).addTo(map);
 var sz=map.getSize(),b=map.getBounds(),sw=L.CRS.EPSG3857.project(b.getSouthWest()),ne=L.CRS.EPSG3857.project(b.getNorthEast()),pt=map.latLngToContainerPoint(ev.latlng),P=L.CRS.EPSG3857.project(ev.latlng);
 var bb={b:[sw.x,sw.y,ne.x,ne.y].join(","),w:sz.x,h:sz.y,i:Math.round(pt.x),j:Math.round(pt.y)};
 var box=$("sel");box.style.display="block";box.innerHTML='<div class="selh"><b>Selezione</b><button id="selX" class="mini">✖ Deseleziona</button></div><div id="selR">Interrogo '+viv.length+' strati accesi…</div>';
 $("selX").onclick=pulisciSel;
 var attese=viv.length,tutti=[],primo=false,falliti=[];
 function gfi(u,l,fmt,n){return u+"&SERVICE=WMS&VERSION=1.3.0&REQUEST=GetFeatureInfo&LAYERS="+encodeURIComponent(l)+"&QUERY_LAYERS="+encodeURIComponent(l)+"&STYLES=&CRS=EPSG:3857&BBOX="+bb.b+"&WIDTH="+bb.w+"&HEIGHT="+bb.h+"&I="+bb.i+"&J="+bb.j+"&INFO_FORMAT="+fmt+"&FEATURE_COUNT="+n}
 function trovato(e,r){if(r.length){tutti.push({t:e.t,r:r});if(!primo&&e.tipo!="agx"){primo=true;evidenzia(e.u,e.l,bb)}}}
 function eccezione(t){return /ServiceException|ExceptionReport/.test(t)}
 viv.forEach(function(e){
  var p;
  if(e.tipo=="plain")p=rete("GET",gfi(e.u,e.l,"text/plain",5)).then(function(t){if(eccezione(t)){falliti.push(e.t);return}trovato(e,righeDaPlain(t,e.l))});
  else if(e.tipo=="agx")p=rete("GET",e.u+"/identify?f=json&geometry="+P.x+","+P.y+"&geometryType=esriGeometryPoint&sr=3857&layers=all:"+encodeURIComponent(e.l)+"&tolerance=1&mapExtent="+bb.b+"&imageDisplay="+bb.w+","+bb.h+",96&returnGeometry=false").then(function(t){
    var d=JSON.parse(t);if(d.error){falliti.push(e.t);return}
    var r=[];(d.results||[]).slice(0,5).forEach(function(x){var a=x.attributes||{},righe=[];Object.keys(a).forEach(function(k){var v=a[k];if(v==null||String(v).trim()===""||v==="Null"||CAMPI_OMESSI.test(k)||righe.length>=12)return;righe.push(k.replace(/_/g," ")+": "+v)});if(righe.length)r.push({righe:righe,link:[]})});
    trovato(e,r)});
  else p=rete("GET",gfi(e.u,e.l,"text/plain",5)).then(function(t2){
    /* presenza: conta solo il testo/plain (elementi realmente nel punto); l'HTML del Geoscopio mostra titolo e link anche a strato vuoto */
    if(eccezione(t2)){falliti.push(e.t);return}
    if(!/\n\s*Feature \d+:/.test(t2))return;
    if(/usicivici/.test(e.l)){var pu=(t2.match(/PRES_UC = '([^']*)'/)||[])[1]||"";if(!pu.trim())return}
    return rete("GET",gfi(e.u,e.l,"text/html",5)).then(function(t){
      var r=eccezione(t)?[]:riceviInfo(t);
      if(!r.length)r=righeDaPlain(t2,e.l);
      if(!r.length)r=[{righe:["Elemento presente in questo punto"],link:[]}];
      trovato(e,r)}).catch(function(){trovato(e,righeDaPlain(t2,e.l).length?righeDaPlain(t2,e.l):[{righe:["Elemento presente in questo punto"],link:[]}])})});
  p.catch(function(){falliti.push(e.t)}).then(function(){if(--attese==0)mostraSel(tutti,{interrogati:viv.map(function(x){return x.t}),falliti:falliti,nonInterr:A.nonInterr,tagliati:A.v.length-viv.length})})})}
function mostraSel(tutti,info){
 var el=$("selR");if(!el)return;info=info||{};
 var h="";
 if(!tutti.length)h+='<div class="selb">Nessun elemento in questo punto negli strati accesi. Avvicinati o tocca dentro l\'area di uno strato.</div>';
 tutti.forEach(function(x,i){h+='<div class="selb"><b>'+esc(x.t)+'</b>';
  x.r.forEach(function(bl,j){h+='<div class="selo">';
   bl.righe.forEach(function(r){h+='<div>'+esc(r)+'</div>'});
   var visti={};bl.link.forEach(function(l,k){if(visti[l.u])return;visti[l.u]=1;h+='<div><button class="dl" data-i="'+i+'" data-j="'+j+'" data-k="'+k+'">📥 '+esc(l.t)+'</button></div>'});
   h+='</div>'});
  h+='</div>'});
 if(info.interrogati){
  if(info.nonInterr&&info.nonInterr.length)h+='<p class="pic">Accesi ma non interrogabili: '+esc(info.nonInterr.join(", "))+'.</p>';
  if(info.falliti&&info.falliti.length)h+='<p class="pic">Non hanno risposto o non sono interrogabili: '+esc(info.falliti.join(", "))+'.</p>';
  if(info.tagliati>0)h+='<p class="pic">Altri '+info.tagliati+' strati accesi non sono stati interrogati (massimo 60 per tocco): spegni quelli che non servono.</p>'}
 h+='<p class="pic">Dati della Regione Toscana – Geoscopio. Il file si scarica con il browser del telefono.</p>';
 el.innerHTML=h;
 el.querySelectorAll("button.dl").forEach(function(b){b.onclick=function(){apri(tutti[+b.dataset.i].r[+b.dataset.j].link[+b.dataset.k].u)}})}
window.selezioneClick=function(ev){
 if(VINC.on){chiudiTutto();vincoliPunto(ev);return true}
 if(!SEL.on)return false;
 chiudiTutto();interroga(ev);return true};

/* ---------- macchina del tempo ---------- */
/* L'elenco è costruito da tutte le ortofoto del Geoscopio (1954 → oggi), esclusi i falsi colori. */
var TM={on:false,i:0,j:0,cmp:false,f:.5,A:null,B:null,lista:[]};
map.createPane("tmA").style.zIndex=250;map.createPane("tmB").style.zIndex=251;
function eTm(l){return {u:l.srv,l:l.name,t:titoloCorto(l),anno:l.anno}}
function caricaListaTm(){
 return caricaOfc().then(function(v){
  var L=v.filter(function(l){return !/false color|\bnir\b|\dR \dG \dB/i.test(l.title+" "+l.name)}).map(eTm);
  L.sort(function(a,b){return a.anno-b.anno||a.t.localeCompare(b.t)});
  TM.lista=L;
  var h="";L.forEach(function(x,k){h+='<option value="'+k+'">'+esc(x.t)+'</option>'});
  $("tmY").innerHTML=h;$("tmJ").innerHTML=h.replace(/<option /g,'<option ');
  $("tmS").max=Math.max(0,L.length-1);
  return L})}
function cercaTm(sel,def){if(!sel)return def;for(var k=0;k<TM.lista.length;k++)if(TM.lista[k].u==sel.u&&TM.lista[k].l==sel.l)return k;return def}
function mkTm(x,pane){var o={layers:x.l,format:"image/jpeg",transparent:false,version:"1.3.0",attribution:ATTR_RT,maxZoom:21,tileSize:256,pane:pane};
 if(REV)o._r=REV;var t=L.tileLayer.wms(x.u,o);
 t.on("tileerror",function(){msg("Questo volo non copre la zona visibile")});return t}
function clipTm(){
 if(!TM.B)return;
 var nw=map.containerPointToLayerPoint([0,0]),se=map.containerPointToLayerPoint(map.getSize());
 var x=nw.x+(se.x-nw.x)*TM.f;
 if(TM.A&&TM.A.getContainer())TM.A.getContainer().style.clip="rect("+nw.y+"px,"+x+"px,"+se.y+"px,"+nw.x+"px)";
 if(TM.B.getContainer())TM.B.getContainer().style.clip="rect("+nw.y+"px,"+se.x+"px,"+se.y+"px,"+x+"px)"}
function disegnaTm(){
 if(TM.A){map.removeLayer(TM.A);TM.A=null}if(TM.B){map.removeLayer(TM.B);TM.B=null}
 var bar=$("tempo");
 if(!TM.on||!TM.lista.length){bar.style.display="none";$("tmDiv").style.display="none";if(!TM.on){STATO.tm={on:false};salva()}return}
 bar.style.display="block";
 var a=TM.lista[TM.i];TM.A=mkTm(a,"tmA").addTo(map);
 $("tmY").value=TM.i;$("tmS").value=TM.i;$("tmN2").textContent=TM.lista.length+" voli disponibili dal "+TM.lista[0].anno+" al "+TM.lista[TM.lista.length-1].anno;
 $("tmC").checked=TM.cmp;$("tmJ").style.display=TM.cmp?"block":"none";
 if(TM.cmp){var b=TM.lista[TM.j];TM.B=mkTm(b,"tmB").addTo(map);$("tmJ").value=TM.j;$("tmDiv").style.display="block";posDiv();setTimeout(clipTm,50)}
 else $("tmDiv").style.display="none";
 var ua=TM.lista[TM.i],ub=TM.lista[TM.j];
 STATO.tm={on:TM.on,v:2,a:{u:ua.u,l:ua.l},b:{u:ub.u,l:ub.l}};salva()}
function posDiv(){$("tmDiv").style.left=(TM.f*100)+"%"}
map.on("move zoom viewreset resize",function(){if(TM.on&&TM.cmp)clipTm()});
$("tmS").oninput=function(){TM.i=+this.value;disegnaTm()};
$("tmY").onchange=function(){TM.i=+this.value;disegnaTm()};
$("tmP").onclick=function(){TM.i=Math.max(0,TM.i-1);disegnaTm()};
$("tmN").onclick=function(){TM.i=Math.min(TM.lista.length-1,TM.i+1);disegnaTm()};
$("tmX").onclick=function(){TM.on=false;TM.cmp=false;disegnaTm()};
$("tmC").onchange=function(){TM.cmp=this.checked;if(TM.cmp&&TM.j==TM.i)TM.j=Math.max(0,TM.i-1);disegnaTm()};
$("tmJ").onchange=function(){TM.j=+this.value;disegnaTm()};
function apriTm(){
 msg("Carico l'elenco delle ortofoto…");
 caricaListaTm().then(function(L){
  if(!L.length){msg("Elenco ortofoto non disponibile: controlla la connessione");return}
  var st=STATO.tm||{};if(st.v!==2)st={}; /* dalla v3.97 si parte dal volo regionale 2024/2025, 1:5.000, colore, su tutta la Toscana */
  var iniz=L.length-1;L.forEach(function(x,k){if(x.l=="rt_ofc.5k24.32bit")iniz=k});
  TM.i=cercaTm(st.a,iniz);TM.j=cercaTm(st.b,Math.max(0,TM.i-1));
  TM.on=true;disegnaTm()})}
(function(){var d=$("tmDiv"),drag=false;
 function mv(e){if(!drag)return;var p=e.touches?e.touches[0]:e;var r=map.getContainer().getBoundingClientRect();TM.f=Math.min(.97,Math.max(.03,(p.clientX-r.left)/r.width));posDiv();clipTm();e.preventDefault()}
 d.addEventListener("touchstart",function(e){drag=true;map.dragging.disable();e.preventDefault()},{passive:false});
 d.addEventListener("mousedown",function(e){drag=true;map.dragging.disable();e.preventDefault()});
 window.addEventListener("touchmove",mv,{passive:false});window.addEventListener("mousemove",mv);
 function fine(){if(drag){drag=false;map.dragging.enable()}}
 window.addEventListener("touchend",fine);window.addEventListener("mouseup",fine)})();
if(STATO.tm&&STATO.tm.on)setTimeout(apriTm,300);

/* ---------- stampa / salva PDF ---------- */
function scalaApprossimata(){var lat=map.getCenter().lat*Math.PI/180;
 var mpp=156543.03392*Math.cos(lat)/Math.pow(2,map.getZoom());var n=Math.round(mpp*96/0.0254/100)*100;return "1:"+n.toLocaleString("it-IT")}
function attivi(){var v=[];
 DEF.forEach(function(d){if(d.on)v.push({t:d.nome,u:SERVIZI[d.srv],l:d.lay.split(",")[0]})});
 if(OFC.on&&OFC.sel)v.push({t:"Ortofoto "+(OFC.sel.t||""),u:OFC.sel.u,l:OFC.sel.l});
 if(TM.on&&TM.lista[TM.i])v.push({t:"Ortofoto "+TM.lista[TM.i].t,u:TM.lista[TM.i].u,l:TM.lista[TM.i].l});
 EXTRA.forEach(function(e){v.push({t:e.t,u:e.u,l:e.l})});return v}
function preparaStampa(titolo,conLegenda){
 var a=attivi();
 var h='<div class="it"><b>'+esc(titolo||"Mappa")+'</b><br><span>Scala circa '+scalaApprossimata()+' · '+new Date().toLocaleDateString("it-IT")+'</span></div>';
 if(conLegenda&&a.length){h+='<div class="lg"><b>Livelli</b>';a.slice(0,8).forEach(function(x){
   var img=/^https:\/\/www502\.regione\.toscana\.it\//.test(x.u)?'<img src="'+x.u+(x.u.indexOf("?")<0?"?":"&")+'SERVICE=WMS&VERSION=1.3.0&REQUEST=GetLegendGraphic&FORMAT=image/png&LAYER='+encodeURIComponent(x.l)+'" onerror="this.style.display=\'none\'">':"";
   h+='<div>'+esc(x.t)+'<br>'+img+'</div>'});
  if(a.length>8)h+='<div>… e altri '+(a.length-8)+' livelli</div>';h+='</div>'}
 h+='<div class="fo">Fonte: Regione Toscana – Geoscopio; sfondo: '+esc((BASI[baseId]||{}).nome||"")+'. Solo consultazione, senza valore certificativo.</div>';
 $("stampaBox").innerHTML=h;document.body.classList.add("stampa");map.invalidateSize()}
window.fineStampa=function(){document.body.classList.remove("stampa");$("stampaBox").innerHTML="";map.invalidateSize()};
function avviaStampa(){
 var t=$("stpT").value.trim(),lg=$("stpL").checked;
 $("stp").style.display="none";
 preparaStampa(t,lg);
 setTimeout(function(){
  if(window.Android&&Android.stampa)Android.stampa(t||"Mappa");
  else{window.print();setTimeout(window.fineStampa,500)}},1500)}
$("stpGo").onclick=avviaStampa;
$("stpNo").onclick=function(){$("stp").style.display="none"};

/* ---------- istruttoria urbanistico-edilizia: gruppi di vincoli ---------- */
var VINC={on:false};
function livG(g){return g.l}
function statoGruppo(g){var n=0;g.l.forEach(function(l){if(haExtra(l.u,l.w))n++});return n==0?0:(n==g.l.length?2:1)}
function accendiLiv(l){if(!haExtra(l.u,l.w))aggiungiExtra(l.u,l.w,l.t,.6)}
function pannelloIstr(){
 var el=$("istrBox");if(!el)return;
 var h='<div class="pic">Accendi con un tocco tutti gli strati di un gruppo: sono solo dati attuali ed essenziali (niente dati storici). Dati di consultazione: per l\'istruttoria fa fede il dato ufficiale degli enti competenti.</div>';
 ISTR.forEach(function(g){
  h+='<div class="srv"><label><input type="checkbox" data-g="'+g.id+'"> <b>'+esc(g.n)+'</b> <span class="pic">('+g.l.length+' strati)</span></label> <a href="#" data-gd="'+g.id+'">dettaglio</a><div id="gd_'+g.id+'" style="display:none">';
  g.l.forEach(function(l,k){h+='<div class="lay"><label><input type="checkbox" data-gl="'+g.id+':'+k+'"'+(haExtra(l.u,l.w)?" checked":"")+'> '+esc(l.t)+'</label></div>'});
  h+='</div></div>'});
 h+='<div class="f"><button id="bVinc" style="flex:1;padding:9px">'+(VINC.on?"✅ ":"📍 ")+'Vincoli nel punto che tocco</button></div><div class="pic">Tocca il pulsante, poi un punto della mappa: restano accesi solo i vincoli che interessano quel punto.</div>';
 el.innerHTML=h;
 ISTR.forEach(function(g){var c=el.querySelector('[data-g="'+g.id+'"]'),st=statoGruppo(g);c.checked=st==2;c.indeterminate=st==1;
  c.onchange=function(){if(c.checked)g.l.forEach(accendiLiv);else g.l.forEach(function(l){togliExtra(l.u,l.w)});disegnaExtra();pannelloIstr()}});
 el.querySelectorAll("[data-gd]").forEach(function(a){a.onclick=function(ev){ev.preventDefault();var d=$("gd_"+a.dataset.gd);d.style.display=d.style.display=="none"?"block":"none"}});
 el.querySelectorAll("[data-gl]").forEach(function(c){c.onchange=function(){var p=c.dataset.gl.split(":"),g=ISTR.filter(function(x){return x.id==p[0]})[0],l=g.l[+p[1]];
  if(c.checked)accendiLiv(l);else togliExtra(l.u,l.w);disegnaExtra();pannelloIstr();var d=$("gd_"+g.id);if(d)d.style.display="block"}});
 $("bVinc").onclick=function(){togliVinc(!VINC.on)}}
window.pannelloIstr=function(){pannelloIstr();if(window.bottoniPannello)bottoniPannello()};
function togliVinc(on){VINC.on=on;if(on){SEL.on=false;aggiornaSel();chiudiTutto();msg("Tocca un punto della mappa: ti mostro e accendo solo i vincoli presenti lì")}else{pulisciSel();msg("Ricerca dei vincoli nel punto disattivata")}pannelloIstr()}
var VINC_GRUPPI={idro:1,pai:1,pae:1,arc:1,cul:1,nat:1};
var CAMPI_OMESSI=/^(pk_?uid|pkuid|objectid|gid|fid|id|idrt|shape|geom|geometry|area|perimetro|shape_leng|shape_area|nota_istru)$/i;
function attributiDa(blocco){var righe=[],m,re=/^\s+([A-Za-z0-9_\.]+) = '(.*)'\s*$/gm;
 while((m=re.exec(blocco))&&righe.length<7){var k=m[1],v=m[2].trim();if(!v||CAMPI_OMESSI.test(k))continue;if(v.length>90)v=v.slice(0,87)+"…";righe.push(k.replace(/_/g," ")+": "+v)}
 return righe}
function datiPunto(blocchi){ /* "Layer 'x' ... Feature ..." -> {nome: testo} */
 var out={},re=/Layer '([^']+)'([\s\S]*?)(?=\nLayer '|$)/g,m;
 while((m=re.exec(blocchi)))out[m[1]]=(out[m[1]]||"")+m[2];return out}
function vincoliPunto(ev){
 pulisciSel();
 marker=L.circleMarker(ev.latlng,{radius:7,color:"#c00",weight:3,fillOpacity:.2}).addTo(map);
 var P=L.CRS.EPSG3857.project(ev.latlng),h=60,bb=[P.x-h,P.y-h,P.x+h,P.y+h].join(",");
 var interr=[];ISTR.forEach(function(g){if(VINC_GRUPPI[g.id])g.l.forEach(function(l){if(l.q)interr.push(l)})});
 var perSrv={},perAgx={};interr.forEach(function(l){var d=l.k=="agx"?perAgx:perSrv;(d[l.u]=d[l.u]||[]).push(l)});
 var box=$("sel");box.style.display="block";
 box.innerHTML='<div class="selh"><b>Vincoli nel punto</b><button id="selX" class="mini">✖ Esci</button></div><div id="selR">Controllo '+interr.length+' strati…</div>';
 $("selX").onclick=function(){togliVinc(false)};
 var trovati={},errori=0;
 function chiedi(u,nomi,info){
  var q=nomi.join(",");
  var url=u+"&SERVICE=WMS&VERSION=1.3.0&REQUEST=GetFeatureInfo&LAYERS="+encodeURIComponent(q)+"&QUERY_LAYERS="+encodeURIComponent(q)+"&STYLES=&CRS=EPSG:3857&BBOX="+bb+"&WIDTH=100&HEIGHT=100&I=50&J=50&INFO_FORMAT=text/plain&FEATURE_COUNT=3";
  return rete("GET",url).then(function(t){
   if(/ServiceException|ExceptionReport/.test(t)&&nomi.length>1){ /* un livello non interrogabile rovina la richiesta: uno alla volta */
     return Promise.all(nomi.map(function(n){return chiedi(u,[n],info)}))}
   if(/ServiceException|ExceptionReport/.test(t)){errori++;return}
   var d=datiPunto(t);Object.keys(d).forEach(function(n){trovati[u+"|"+n]=d[n]})})
  .catch(function(){errori++})}
 var CAMPI_AGX={cod_classe:"classe",classe:"classe",geomorfo:"tipo",decreto_de:"decreto",name:"bacino",pericolo:"pericolosità"};
 function righeAgx(a){var r=[];Object.keys(CAMPI_AGX).forEach(function(k){var v=a[k];if(v==null||String(v).trim()==""||v=="Null")return;
   if(k=="pericolo")v="P"+v;r.push(CAMPI_AGX[k]+": "+v)});return r}
 function chiediAgx(u,ls){
  var ids=ls.map(function(l){return l.w}).join(",");
  var url=u+"/identify?f=json&geometry="+P.x+","+P.y+"&geometryType=esriGeometryPoint&sr=3857&layers=all:"+ids+"&tolerance=1&mapExtent="+bb+"&imageDisplay=100,100,96&returnGeometry=false";
  return rete("GET",url).then(function(t){var d=JSON.parse(t);if(d.error){errori++;return}
   ls.forEach(function(l){trovati[l.u+"|"+l.w]={agx:true,righe:[]}});
   (d.results||[]).forEach(function(x){var k=u+"|"+x.layerId;if(!trovati[k])return;
    righeAgx(x.attributes||{}).forEach(function(r){if(trovati[k].righe.indexOf(r)<0&&trovati[k].righe.length<8)trovati[k].righe.push(r)});
    if(!trovati[k].n)trovati[k].n=0;trovati[k].n++});
   ls.forEach(function(l){var k=l.u+"|"+l.w;if(!trovati[k].n)delete trovati[k]})})
  .catch(function(){errori++})}
 var prom=Object.keys(perSrv).map(function(u){return chiedi(u,perSrv[u].map(function(l){return l.w}))})
  .concat(Object.keys(perAgx).map(function(u){return chiediAgx(u,perAgx[u])}));
 Promise.all(prom).then(function(){
  var elenco=[];
  interr.forEach(function(l){var t=trovati[l.u+"|"+l.w];if(t===undefined)return;
   var attr=t.agx?t.righe:attributiDa(t);
   if(!t.agx&&/usicivici/.test(l.w)){ /* il livello copre tutti i comuni: conta solo se il campo PRES_UC è compilato */
     var pu=(t.match(/PRES_UC = '([^']*)'/)||[])[1]||"";
     if(!pu.trim()){return}}
   elenco.push({l:l,attr:attr})});
  /* accendi solo i vincoli presenti */
  ISTR.forEach(function(g){g.l.forEach(function(l){togliExtra(l.u,l.w)})});
  elenco.forEach(function(x){accendiLiv(x.l)});
  disegnaExtra();pannelloIstr();
  var el=$("selR");if(!el)return;
  var h="";
  if(!elenco.length)h+='<div class="selb">Nessuno dei '+interr.length+' strati di vincolo controllati interessa questo punto.</div>';
  else{h+='<div class="selb"><b>'+elenco.length+' vincoli in questo punto</b> (accesi sulla mappa)</div>';
   elenco.forEach(function(x){h+='<div class="selb"><b>'+esc(x.l.t)+'</b>';x.attr.forEach(function(r){h+='<div>'+esc(r)+'</div>'});h+='</div>'})}
  if(errori)h+='<div class="selb pic">Attenzione: '+errori+' interrogazioni non sono riuscite, l\'elenco può essere incompleto.</div>';
  h+='<p class="pic">Verifica indicativa sui dati WMS della Regione Toscana: per l\'istruttoria fa fede il dato ufficiale degli enti competenti. PAI dissesti e PGRA sono dell\'Autorità di bacino dell\'Appennino Settentrionale (bacino Arno; altri bacini non inclusi).</p>';
  el.innerHTML=h})}

/* ---------- spegni tutto / espandi / comprimi ---------- */
function spegniTutto(){
 DEF.forEach(function(d){if(d.on){d.on=false;map.removeLayer(d.layer);statoLiv(d.id,d)}});
 EXTRA.slice().forEach(function(e){togliExtra(e.u,e.l)});
 OFC.on=false;STATO.ofc={on:false,op:OFC.op};impostaOfc();
 TM.on=false;TM.cmp=false;disegnaTm();
 VINC.on=false;SEL.on=false;aggiornaSel();pulisciSel();
 if(typeof evid!=="undefined"&&evid){map.removeLayer(evid);evid=null}
 salva()}
function tuttiDettagli(aperto){pan.querySelectorAll("details").forEach(function(d){d.open=aperto});ricordaAperti()}
window.bottoniPannello=function(){
 var o=$("bOff");if(!o)return;
 o.onclick=function(){spegniTutto();aperti={};STATO.aperti=aperti;salva();disegnaPannello();pan.style.display="block";tuttiDettagli(false);msg("Tutti gli strati sono spenti e il menu è compresso")};
 $("bEsp").onclick=function(){tuttiDettagli(true)};
 $("bCom").onclick=function(){tuttiDettagli(false)}};

/* ---------- voci del menu ---------- */
var AZIONI={
 tempo:function(){apriTm()},
 leg:function(){disegnaLegenda();leg.style.display="block"},
 vinc:function(){togliVinc(!VINC.on)},
 sel:function(){SEL.on=!SEL.on;aggiornaSel();msg(SEL.on?"Selezione attiva: tocca la mappa, ti mostro i dati di tutti gli strati accesi":"Selezione disattivata")},
 desel:function(){pulisciSel();if(window.pulisciTemp)pulisciTemp();msg("Selezione cancellata")},
 stampa:function(){$("stp").style.display="block"}};
})();
