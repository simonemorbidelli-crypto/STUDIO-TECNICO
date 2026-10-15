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
 $("legX").onclick=function(){EXTRA.slice().forEach(function(e){togliExtra(e.u,e.l)});disegnaExtra();disegnaAlbero();msg("Livelli aggiunti spenti")};
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
function interroga(ev){
 var viv=EXTRA.filter(function(e){return /^https:\/\/www502\.regione\.toscana\.it\//.test(e.u)}).slice(0,6);
 pulisciSel();
 if(!viv.length){msg("Accendi prima un livello dalla legenda (📚 Legenda e cartoteca)");return}
 if(window.marker===undefined)window.marker=null;
 marker=L.circleMarker(ev.latlng,{radius:7,color:"#c00",weight:3,fillOpacity:.2}).addTo(map);
 var sz=map.getSize(),b=map.getBounds(),sw=L.CRS.EPSG3857.project(b.getSouthWest()),ne=L.CRS.EPSG3857.project(b.getNorthEast()),pt=map.latLngToContainerPoint(ev.latlng);
 var bb={b:[sw.x,sw.y,ne.x,ne.y].join(","),w:sz.x,h:sz.y,i:Math.round(pt.x),j:Math.round(pt.y)};
 var box=$("sel");box.style.display="block";box.innerHTML='<div class="selh"><b>Selezione</b><button id="selX" class="mini">✖ Deseleziona</button></div><div id="selR">Interrogo i livelli attivi…</div>';
 $("selX").onclick=pulisciSel;
 var attese=viv.length,tutti=[],primo=false;
 viv.forEach(function(e){
  var url=e.u+"&SERVICE=WMS&VERSION=1.3.0&REQUEST=GetFeatureInfo&LAYERS="+encodeURIComponent(e.l)+"&QUERY_LAYERS="+encodeURIComponent(e.l)+"&STYLES=&CRS=EPSG:3857&BBOX="+bb.b+"&WIDTH="+bb.w+"&HEIGHT="+bb.h+"&I="+bb.i+"&J="+bb.j+"&INFO_FORMAT=text/html&FEATURE_COUNT=5";
  rete("GET",url).then(function(t){var r=riceviInfo(t);
    if(r.length){tutti.push({t:e.t,r:r});if(!primo){primo=true;evidenzia(e.u,e.l,bb)}}})
  .catch(function(){}).then(function(){if(--attese==0)mostraSel(tutti)})})}
function mostraSel(tutti){
 var el=$("selR");if(!el)return;
 if(!tutti.length){el.innerHTML='Nessun elemento selezionabile in questo punto. Avvicinati o tocca dentro un\'area del livello acceso.';return}
 var h="";
 tutti.forEach(function(x,i){h+='<div class="selb"><b>'+esc(x.t)+'</b>';
  x.r.forEach(function(bl,j){h+='<div class="selo">';
   bl.righe.forEach(function(r){h+='<div>'+esc(r)+'</div>'});
   var visti={};bl.link.forEach(function(l,k){if(visti[l.u])return;visti[l.u]=1;h+='<div><button class="dl" data-i="'+i+'" data-j="'+j+'" data-k="'+k+'">📥 '+esc(l.t)+'</button></div>'});
   h+='</div>'});
  h+='</div>'});
 h+='<p class="pic">Dati della Regione Toscana – Geoscopio. Il file si scarica con il browser del telefono.</p>';
 el.innerHTML=h;
 el.querySelectorAll("button.dl").forEach(function(b){b.onclick=function(){apri(tutti[+b.dataset.i].r[+b.dataset.j].link[+b.dataset.k].u)}})}
window.selezioneClick=function(ev){
 if(!SEL.on)return false;
 var viv=EXTRA.some(function(e){return /^https:\/\/www502\.regione\.toscana\.it\//.test(e.u)});
 if(!viv)return false;
 chiudiTutto();interroga(ev);return true};

/* ---------- macchina del tempo ---------- */
var TM={on:false,i:GEOS.TM.length-1,j:0,cmp:false,f:.5,A:null,B:null};
try{var st=STATO.tm;if(st){TM.i=Math.min(Math.max(+st.i||0,0),GEOS.TM.length-1);TM.j=Math.min(Math.max(+st.j||0,0),GEOS.TM.length-1);}}catch(e){}
map.createPane("tmA").style.zIndex=250;map.createPane("tmB").style.zIndex=251;
function annoTm(l){var m=l.d.match(/(\d{4}(?:\/\d{4})?)/);return m?m[1]:l.d}
function mkTm(l,pane){var o={layers:l.w,format:"image/jpeg",transparent:false,version:"1.3.0",attribution:ATTR_RT,maxZoom:21,tileSize:256,pane:pane};
 if(REV)o._r=REV;var t=L.tileLayer.wms(S[l.s],o);
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
 if(!TM.on){bar.style.display="none";$("tmDiv").style.display="none";return}
 bar.style.display="block";
 var a=GEOS.TM[TM.i];TM.A=mkTm(a,"tmA").addTo(map);
 $("tmY").textContent=annoTm(a);$("tmD").textContent=a.d;$("tmS").value=TM.i;
 var cb=$("tmC");cb.checked=TM.cmp;
 $("tmJ").style.display=TM.cmp?"block":"none";
 if(TM.cmp){var b=GEOS.TM[TM.j];TM.B=mkTm(b,"tmB").addTo(map);$("tmJ").value=TM.j;$("tmDiv").style.display="block";posDiv();setTimeout(clipTm,50)}
 else $("tmDiv").style.display="none";
 STATO.tm={on:TM.on,i:TM.i,j:TM.j};salva()}
function posDiv(){$("tmDiv").style.left=(TM.f*100)+"%"}
map.on("move zoom viewreset resize",function(){if(TM.on&&TM.cmp)clipTm()});
$("tmS").oninput=function(){TM.i=+this.value;disegnaTm()};
$("tmP").onclick=function(){TM.i=Math.max(0,TM.i-1);disegnaTm()};
$("tmN").onclick=function(){TM.i=Math.min(GEOS.TM.length-1,TM.i+1);disegnaTm()};
$("tmX").onclick=function(){TM.on=false;TM.cmp=false;disegnaTm()};
$("tmC").onchange=function(){TM.cmp=this.checked;if(TM.cmp&&TM.j==TM.i)TM.j=Math.max(0,TM.i-1);disegnaTm()};
$("tmJ").onchange=function(){TM.j=+this.value;disegnaTm()};
(function(){var h="";GEOS.TM.forEach(function(l,k){h+='<option value="'+k+'">Confronta con '+esc(annoTm(l))+'</option>'});$("tmJ").innerHTML=h})();
(function(){var d=$("tmDiv"),drag=false;
 function mv(e){if(!drag)return;var p=e.touches?e.touches[0]:e;var r=map.getContainer().getBoundingClientRect();TM.f=Math.min(.97,Math.max(.03,(p.clientX-r.left)/r.width));posDiv();clipTm();e.preventDefault()}
 d.addEventListener("touchstart",function(e){drag=true;map.dragging.disable();e.preventDefault()},{passive:false});
 d.addEventListener("mousedown",function(e){drag=true;map.dragging.disable();e.preventDefault()});
 window.addEventListener("touchmove",mv,{passive:false});window.addEventListener("mousemove",mv);
 function fine(){if(drag){drag=false;map.dragging.enable()}}
 window.addEventListener("touchend",fine);window.addEventListener("mouseup",fine)})();
if(STATO.tm&&STATO.tm.on){TM.on=true;disegnaTm()}

/* ---------- stampa / salva PDF ---------- */
function scalaApprossimata(){var lat=map.getCenter().lat*Math.PI/180;
 var mpp=156543.03392*Math.cos(lat)/Math.pow(2,map.getZoom());var n=Math.round(mpp*96/0.0254/100)*100;return "1:"+n.toLocaleString("it-IT")}
function attivi(){var v=[];
 DEF.forEach(function(d){if(d.on)v.push({t:d.nome,u:SERVIZI[d.srv],l:d.lay.split(",")[0]})});
 if(OFC.on&&OFC.sel)v.push({t:"Ortofoto "+(OFC.sel.t||""),u:OFC.sel.u,l:OFC.sel.l});
 if(TM.on)v.push({t:"Ortofoto "+annoTm(GEOS.TM[TM.i]),u:S[GEOS.TM[TM.i].s],l:GEOS.TM[TM.i].w});
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

/* ---------- voci del menu ---------- */
var AZIONI={
 tempo:function(){TM.on=true;disegnaTm()},
 leg:function(){disegnaLegenda();leg.style.display="block"},
 sel:function(){SEL.on=!SEL.on;aggiornaSel();msg(SEL.on?"Selezione attiva: tocca un elemento dei livelli accesi (legenda)":"Selezione disattivata")},
 desel:function(){pulisciSel();msg("Selezione cancellata")},
 stampa:function(){$("stp").style.display="block"}};
})();
