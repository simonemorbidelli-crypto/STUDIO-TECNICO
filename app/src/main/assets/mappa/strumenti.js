/* Menu a tocco prolungato sulla mappa (v3.97): le voci del "tasto destro" del Geoscopio, adattate al telefono.
   Nota · Vai a posizione · Rilascia indicatore xy · Recupera quota · Disegna cerchio · Cancella cerchio.
   Usa le funzioni della pagina principale (map, msg, rete, esc, STATO, salva, proj4). */
(function(){
var DTM_URL="https://www502.regione.toscana.it/wmsraster/com.rt.wms.RTmap/wms?map=wmsmorfologia";
var DTM_LAYER="rt_morfologia.iddtm.1m.irs";

/* ---------- stile ---------- */
var st=document.createElement("style");
st.textContent=
"#ctx{position:absolute;z-index:1002;background:#fffffff5;border:1px solid #999;border-radius:10px;padding:4px;display:none;font-size:15px;min-width:215px;max-width:80%;box-shadow:0 2px 8px #0005}"+
"#ctx button{display:block;width:100%;text-align:left;border:0;background:none;padding:11px 10px;font-size:15px;border-bottom:1px solid #eee}"+
"#ctx button:last-child{border-bottom:0}"+
"#ctx .t{font-size:11px;color:#555;padding:4px 10px 6px;border-bottom:1px solid #ddd}"+
"#dlg{position:absolute;z-index:1003;left:8px;right:8px;top:64px;background:#fff;border:1px solid #666;border-radius:10px;padding:10px 12px;display:none;box-shadow:0 2px 12px #0006;font-size:14px;max-height:80%;overflow:auto}"+
"#dlg h4{margin:0 0 6px}#dlg .f{display:flex;gap:6px;margin:6px 0}#dlg .f>*{flex:1;min-width:0;padding:8px;font-size:15px;border:1px solid #999;border-radius:6px;box-sizing:border-box}"+
"#dlg textarea{width:100%;min-height:90px;box-sizing:border-box;padding:8px;font-size:15px;border:1px solid #999;border-radius:6px}"+
"#dlg .err{color:#b3261e;font-size:13px;min-height:16px}"+
".pin{font-size:22px;line-height:24px;text-shadow:0 0 3px #fff}"+
".pop button{margin-top:6px;padding:6px 10px;font-size:13px;border:1px solid #999;border-radius:6px;background:#fff}"+
"body.stampa #ctx,body.stampa #dlg{display:none!important}";
document.head.appendChild(st);

var ctx=document.createElement("div");ctx.id="ctx";document.body.appendChild(ctx);
var dlg=document.createElement("div");dlg.id="dlg";document.body.appendChild(dlg);

/* ---------- sistemi di coordinate ---------- */
var CRS=[
 {id:"w",n:"WGS84 gradi (lat, lon)",geo:true,e1:"Latitudine",e2:"Longitudine"},
 {id:"u",n:"UTM 32N WGS84/ETRS89 – EPSG:32632 (E, N)",def:"EPSG:32632",e1:"Est (m)",e2:"Nord (m)"},
 {id:"g",n:"Gauss-Boaga Ovest Roma40 – EPSG:3003 (E, N) – indicativo",def:"EPSG:3003",e1:"Est (m)",e2:"Nord (m)"},
 {id:"m",n:"Web Mercator – EPSG:3857 (X, Y)",def:"EPSG:3857",e1:"X (m)",e2:"Y (m)"}];
function crs(id){for(var i=0;i<CRS.length;i++)if(CRS[i].id==id)return CRS[i];return CRS[0]}
function daLatLng(ll,id){var c=crs(id);if(c.geo)return [ll.lat,ll.lng];var p=proj4("EPSG:4326",c.def,[ll.lng,ll.lat]);return [p[0],p[1]]}
function aLatLng(a,b,id){var c=crs(id);if(c.geo)return L.latLng(a,b);var p=proj4(c.def,"EPSG:4326",[a,b]);return L.latLng(p[1],p[0])}
function fmtCoord(ll,id){var c=crs(id),v=daLatLng(ll,id);
 if(c.geo)return "lat "+v[0].toFixed(7)+"\nlon "+v[1].toFixed(7);
 return "E "+v[0].toFixed(2)+"\nN "+v[1].toFixed(2)}
function num(t){return parseFloat(String(t).trim().replace(",","."))}
function nf(x,d){return x.toLocaleString("it-IT",{minimumFractionDigits:d,maximumFractionDigits:d})}

/* ---------- cose disegnate (indicatori, quote, cerchio, note) ---------- */
var temp=L.layerGroup().addTo(map);          /* indicatori xy, quote, posizioni: si tolgono con "Deseleziona tutto" */
var cerchio=null;                             /* un solo cerchio alla volta */
var noteL=L.layerGroup().addTo(map);          /* note: restano finché non le elimini */
function pin(ll,emoji){return L.marker(ll,{icon:L.divIcon({html:emoji,className:"pin",iconSize:[26,26],iconAnchor:[13,22]}),keyboard:false})}
window.pulisciTemp=function(){temp.clearLayers()};
function popupHtml(righe,bottone){var h='<div class="pop">'+righe.map(function(r){return esc(r)}).join("<br>");if(bottone)h+='<br><button data-rm="1">'+bottone+'</button>';return h+'</div>'}
function conRimuovi(m,righe){
 m.bindPopup(popupHtml(righe,"🗑 Rimuovi"),{autoPan:true});
 m.on("popupopen",function(e){var b=e.popup.getElement().querySelector("[data-rm]");if(b)b.onclick=function(){m.remove()}})}

/* ---------- finestra di dialogo ---------- */
function chiudiDlg(){dlg.style.display="none";dlg.innerHTML=""}
function apriDlg(html){chiudiTutto();nascondiCtx();dlg.innerHTML=html;dlg.style.display="block"}

/* ---------- menu a tocco prolungato ---------- */
var puntoCtx=null,aperturaCtx=0;
function nascondiCtx(){ctx.style.display="none"}
function mostraCtx(ll,pt){
 puntoCtx=ll;aperturaCtx=Date.now();window.__lp=aperturaCtx;
 var h='<div class="t">'+ll.lat.toFixed(6)+', '+ll.lng.toFixed(6)+'</div>'+
  '<button data-c="nota">📝 Nota</button>'+
  '<button data-c="vai">📍 Vai a posizione…</button>'+
  '<button data-c="xy">📌 Rilascia indicatore xy</button>'+
  '<button data-c="quota">⛰ Recupera quota</button>'+
  '<button data-c="cerchio">⭕ Disegna cerchio</button>'+
  '<button data-c="canc">🗑 Cancella cerchio</button>';
 ctx.innerHTML=h;ctx.style.display="block";
 var W=document.body.clientWidth,H=document.body.clientHeight,w=ctx.offsetWidth,hh=ctx.offsetHeight;
 ctx.style.left=Math.max(6,Math.min(W-w-6,pt.x-w/2))+"px";
 ctx.style.top=Math.max(60,Math.min(H-hh-6,pt.y-hh-14))+"px";
 ctx.querySelectorAll("button").forEach(function(b){b.onclick=function(ev){ev.stopPropagation();azione(b.dataset.c)}})}
function apriDaEvento(ev){ /* ev: evento Leaflet con latlng e containerPoint */
 if(Date.now()-aperturaCtx<900)return;
 var pt=ev.containerPoint||map.latLngToContainerPoint(ev.latlng);
 mostraCtx(ev.latlng,pt)}

/* tocco prolungato (circa 0,65 s) su schermo tattile; tasto destro con il mouse */
(function(){
 var cont=map.getContainer(),lp=null;
 function annulla(){if(lp){clearTimeout(lp.id);lp=null}}
 cont.addEventListener("touchstart",function(e){
  if(e.touches.length!=1){annulla();return}
  var t=e.touches[0];
  lp={x:t.clientX,y:t.clientY,fired:false,id:setTimeout(function(){
   if(!lp)return;lp.fired=true;window.__lp=Date.now();
   if(Date.now()-aperturaCtx<900)return;
   var r=cont.getBoundingClientRect(),pt=L.point(lp.x-r.left,lp.y-r.top);
   mostraCtx(map.containerPointToLatLng(pt),pt)},650)}},{passive:true});
 cont.addEventListener("touchmove",function(e){
  if(!lp)return;var t=e.touches[0];
  if(Math.abs(t.clientX-lp.x)>12||Math.abs(t.clientY-lp.y)>12)annulla()},{passive:true});
 function fine(e){if(lp&&lp.fired&&e.cancelable)e.preventDefault();annulla()}
 cont.addEventListener("touchend",fine,{passive:false});
 cont.addEventListener("touchcancel",annulla,{passive:true});
 map.on("contextmenu",apriDaEvento);
 map.on("click movestart zoomstart",function(){if(Date.now()-aperturaCtx>400)nascondiCtx()})})();

function azione(c){
 var ll=puntoCtx;if(!ll)return;
 if(c=="nota")dialogNota(ll);
 else if(c=="vai")dialogVai(ll);
 else if(c=="xy")sceltaCrs(ll);
 else if(c=="quota"){nascondiCtx();recuperaQuota(ll)}
 else if(c=="cerchio")dialogCerchio(ll);
 else if(c=="canc"){nascondiCtx();cancellaCerchio()}}

/* ---------- Nota ---------- */
function autolink(t){return esc(t).replace(/(https:\/\/[^\s<]+)/g,'<a href="#" data-u="$1">$1</a>').replace(/\n/g,"<br>")}
function creaNota(n,salvaSub){
 var ll=L.latLng(n.lat,n.lng),m=pin(ll,"📝");
 m.bindPopup('<div class="pop">'+autolink(n.t)+'<br><button data-rm="1">🗑 Elimina nota</button></div>',{autoPan:true,maxWidth:260});
 m.on("popupopen",function(e){var el=e.popup.getElement();
  var b=el.querySelector("[data-rm]");if(b)b.onclick=function(){noteL.removeLayer(m);STATO.note=(STATO.note||[]).filter(function(x){return x!==n&&!(x.lat==n.lat&&x.lng==n.lng&&x.t==n.t)});salva()};
  el.querySelectorAll("a[data-u]").forEach(function(a){a.onclick=function(ev){ev.preventDefault();if(window.Android&&Android.apri)Android.apri(a.dataset.u)}})});
 m.addTo(noteL);
 if(salvaSub){STATO.note=(STATO.note||[]).concat([n]);salva()}
 return m}
(STATO.note||[]).forEach(function(n){if(n&&typeof n.lat=="number"&&typeof n.lng=="number"&&n.t)creaNota(n,false)});
function dialogNota(ll){
 apriDlg('<h4>📝 Nota</h4><div class="pic">Scrivi la nota: resta sulla mappa (anche alla riapertura) finché non la elimini. I link che cominciano con https:// diventano toccabili.</div>'+
  '<textarea id="nT" placeholder="Testo della nota"></textarea><div class="err" id="nE"></div>'+
  '<div class="f"><button id="nOk">OK</button><button id="nNo">Annulla</button></div>');
 document.getElementById("nNo").onclick=chiudiDlg;
 document.getElementById("nOk").onclick=function(){
  var t=document.getElementById("nT").value.trim();
  if(!t){document.getElementById("nE").textContent="Scrivi almeno una parola";return}
  var m=creaNota({lat:ll.lat,lng:ll.lng,t:t.slice(0,1500)},true);chiudiDlg();m.openPopup()};
 setTimeout(function(){var t=document.getElementById("nT");if(t)t.focus()},50)}

/* ---------- Vai a posizione ---------- */
function opzioniCrs(sel){return CRS.map(function(c){return '<option value="'+c.id+'"'+(c.id==sel?" selected":"")+'>'+esc(c.n)+'</option>'}).join("")}
function dialogVai(ll){
 apriDlg('<h4>📍 Vai a posizione</h4><div class="pic">Scegli il sistema di coordinate e scrivi le coordinate del punto (virgola o punto per i decimali).</div>'+
  '<div class="f"><select id="vC">'+opzioniCrs("w")+'</select></div>'+
  '<div class="f"><input id="vA" inputmode="decimal" placeholder="Latitudine"><input id="vB" inputmode="decimal" placeholder="Longitudine"></div>'+
  '<div class="err" id="vE"></div><div class="f"><button id="vGo">Vai</button><button id="vNo">Annulla</button></div>');
 var C=document.getElementById("vC"),A=document.getElementById("vA"),B=document.getElementById("vB");
 function riempi(){var c=crs(C.value),v=daLatLng(ll,c.id);A.placeholder=c.e1;B.placeholder=c.e2;
  A.value=c.geo?v[0].toFixed(6):v[0].toFixed(1);B.value=c.geo?v[1].toFixed(6):v[1].toFixed(1)}
 riempi();C.onchange=riempi;
 document.getElementById("vNo").onclick=chiudiDlg;
 document.getElementById("vGo").onclick=function(){
  var a=num(A.value),b=num(B.value),E=document.getElementById("vE");
  if(isNaN(a)||isNaN(b)){E.textContent="Scrivi entrambe le coordinate";return}
  var p;try{p=aLatLng(a,b,C.value)}catch(e){E.textContent="Coordinate non valide";return}
  if(!(p.lat>34&&p.lat<48&&p.lng>5&&p.lng<20)){E.textContent="Il punto cade fuori dall'Italia: controlla il sistema scelto e l'ordine Est/Nord";return}
  chiudiDlg();
  var m=pin(p,"📍").addTo(temp);conRimuovi(m,[crs(C.value).n,fmtCoord(p,C.value)]);
  map.setView(p,Math.max(map.getZoom(),17));m.openPopup()}}

/* ---------- Rilascia indicatore xy ---------- */
function sceltaCrs(ll){
 var h='<div class="t">Coordinate nel sistema…</div>';
 CRS.forEach(function(c){h+='<button data-s="'+c.id+'">'+esc(c.n)+'</button>'});
 h+='<button data-s="">Annulla</button>';
 ctx.innerHTML=h;
 ctx.querySelectorAll("button").forEach(function(b){b.onclick=function(ev){ev.stopPropagation();nascondiCtx();
  if(!b.dataset.s)return;
  var m=pin(ll,"📌").addTo(temp);conRimuovi(m,[crs(b.dataset.s).n,fmtCoord(ll,b.dataset.s)]);m.openPopup()}})}

/* ---------- Recupera quota (DTM 1 m della Regione Toscana) ---------- */
function recuperaQuota(ll){
 var m=pin(ll,"⛰").addTo(temp);
 m.bindPopup("Cerco la quota…").openPopup();
 var P=L.CRS.EPSG3857.project(ll),h=60;
 var url=DTM_URL+"&SERVICE=WMS&VERSION=1.3.0&REQUEST=GetFeatureInfo&LAYERS="+DTM_LAYER+"&QUERY_LAYERS="+DTM_LAYER+"&STYLES=&CRS=EPSG:3857&BBOX="+[P.x-h,P.y-h,P.x+h,P.y+h].join(",")+"&WIDTH=100&HEIGHT=100&I=50&J=50&INFO_FORMAT=text/html&FEATURE_COUNT=1";
 rete("GET",url).then(function(t){
  var s=String(t).replace(/<script[\s\S]*?<\/script>/gi," ").replace(/<[^>]+>/g," ").replace(/&nbsp;/g," ").replace(/\s+/g," ");
  var q=s.match(/Quota \(m\):\s*\[\s*(-?[\d.,]+)\s*\]/);
  var righe;
  if(q){var v=parseFloat(q[1].replace(",","."));
   righe=["Quota: "+nf(v,2)+" m","DTM 1 m orografico – Regione Toscana","lat "+ll.lat.toFixed(6)+"  lon "+ll.lng.toFixed(6),"Dato di consultazione: la Regione non l'ha sottoposto alle consuete verifiche di conformità."]}
  else righe=["Quota non disponibile in questo punto.","Il DTM a 1 metro della Regione non copre la zona (o il punto è fuori dalla Toscana)."];
  conRimuovi(m,righe);m.openPopup()})
 .catch(function(){m.setPopupContent("Quota non ottenuta: controlla la connessione e riprova.")})}

/* ---------- Disegna cerchio / Cancella cerchio ---------- */
var ULTIMO_RAGGIO=parseFloat(STATO.raggio)||100;
function dialogCerchio(ll){
 apriDlg('<h4>⭕ Disegna cerchio</h4><div class="pic">Il cerchio è centrato nel punto che hai premuto. Ne resta uno solo alla volta: disegnarne un altro cancella il precedente.</div>'+
  '<div class="f"><input id="cR" inputmode="decimal" value="'+ULTIMO_RAGGIO+'" placeholder="Raggio in metri"><span style="flex:0 0 auto;padding:8px 0">metri</span></div>'+
  '<div class="err" id="cE"></div><div class="f"><button id="cOk">Disegna</button><button id="cNo">Annulla</button></div>');
 document.getElementById("cNo").onclick=chiudiDlg;
 document.getElementById("cOk").onclick=function(){
  var r=num(document.getElementById("cR").value);
  if(!(r>0&&r<=500000)){document.getElementById("cE").textContent="Raggio non valido (da 1 m a 500 km)";return}
  ULTIMO_RAGGIO=r;STATO.raggio=r;salva();chiudiDlg();disegnaCerchio(ll,r)}}
function disegnaCerchio(ll,r){
 cancellaCerchio(true);
 var g=L.layerGroup().addTo(map);
 L.circle(ll,{radius:r,color:"#e07000",weight:3,dashArray:"6 4",fillColor:"#ffb347",fillOpacity:.12}).addTo(g);
 var c=pin(ll,"⭕").addTo(g);
 var area=Math.PI*r*r,circ=2*Math.PI*r;
 c.bindPopup(popupHtml(["Raggio: "+nf(r,r<10?2:1)+" m","Circonferenza: "+nf(circ,1)+" m","Area: "+nf(area,1)+" m²"+(area>=10000?" ("+nf(area/10000,2)+" ha)":"")+"  (piano tangente)"]),{autoPan:true});
 cerchio=g;
 map.fitBounds(L.latLng(ll).toBounds(r*2.6),{maxZoom:map.getZoom()>19?map.getZoom():19});
 c.openPopup()}
function cancellaCerchio(silenzioso){
 if(cerchio){map.removeLayer(cerchio);cerchio=null;if(!silenzioso)msg("Cerchio cancellato")}
 else if(!silenzioso)msg("Nessun cerchio da cancellare")}

})();
