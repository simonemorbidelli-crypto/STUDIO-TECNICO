import json
from collections import defaultdict

with open("/tmp/comuni.json", "r", encoding="utf-8") as f:
    data = json.load(f)

# Raggruppa per provincia: { "Arezzo": ["Anghiari", "Arezzo", ...], ... }
per_provincia = defaultdict(list)
for c in data:
    provincia_nome = c["provincia"]["nome"]
    per_provincia[provincia_nome].append(c["nome"])

# Ordina comuni alfabeticamente dentro ogni provincia, e le province stesse
risultato = {}
for provincia in sorted(per_provincia.keys()):
    risultato[provincia] = sorted(per_provincia[provincia])

out_path = "/home/claude/SchedaCliente/app/src/main/assets/comuni_per_provincia.json"
import os
os.makedirs(os.path.dirname(out_path), exist_ok=True)
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(risultato, f, ensure_ascii=False, separators=(",", ":"))

print(f"Province: {len(risultato)}")
print(f"Comuni totali: {sum(len(v) for v in risultato.values())}")
print(f"File size: {os.path.getsize(out_path)} bytes")
# sanity check su Arezzo
print("Arezzo:", risultato.get("Arezzo", [])[:10])
