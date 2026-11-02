// Saca la lista de cupones de la página de soloprecio (viene adentro de los self.__next_f.push de Next.js).
function leerCupones(html) {
  let texto = '';
  const re = /self\.__next_f\.push\((\[[\s\S]*?\])\)<\/script>/g;
  let m;
  while ((m = re.exec(html))) {
    try { const a = JSON.parse(m[1]); if (typeof a[1] === 'string') texto += a[1]; } catch (e) {}
  }
  const i = texto.indexOf('"cupones":[');
  if (i < 0) throw new Error('No se encontró la lista de cupones en la página');
  let j = i + 10, prof = 0, enTexto = false, esc = false;
  for (; j < texto.length; j++) {
    const c = texto[j];
    if (enTexto) { if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') enTexto = false; continue; }
    if (c === '"') enTexto = true;
    else if (c === '[') prof++;
    else if (c === ']') { prof--; if (prof === 0) break; }
  }
  const lista = JSON.parse(texto.slice(i + 10, j + 1));
  // Next escribe "$$" para un "$" literal
  const limpiar = (v) => typeof v === 'string' ? v.replace(/\$\$/g, '$') : v;
  return lista.map((c) => { const o = {}; for (const k in c) o[k] = Array.isArray(c[k]) ? c[k].map(limpiar) : limpiar(c[k]); return o; });
}
if (typeof module !== 'undefined') module.exports = { leerCupones };
