import { readFile } from 'node:fs/promises';

function requireValid(condition, message) {
  if (!condition) throw new Error(message);
}
function decode(value) {
  requireValid(!value.includes('<'), 'символ < в атрибуте XML');
  const entities = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
  return value.replace(/&(?:#x([\da-f]+);|#(\d+);|([a-z]+);)?/gi, (entity, hex, decimal, name) => {
    if (Object.hasOwn(entities, name)) return entities[name];
    const code = hex ? parseInt(hex, 16) : Number(decimal);
    requireValid((hex || decimal) && code > 0 && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff), `неверная сущность XML: ${entity}`);
    return String.fromCodePoint(code);
  });
}
function attributes(text) {
  const result = Object.create(null);
  const pattern = /\s+([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/gy;
  let offset = 0;
  while (text.slice(offset).trim()) {
    pattern.lastIndex = offset;
    const match = pattern.exec(text);
    requireValid(match && !Object.hasOwn(result, match[1]), 'неверные или повторные атрибуты XML');
    result[match[1]] = decode(match[2] ?? match[3]);
    offset = pattern.lastIndex;
  }
  return result;
}
function parseReport(xml) {
  const document = { children: [] };
  const stack = [document];
  // DOCTYPE JaCoCo пропускается без загрузки внешнего report.dtd.
  const tokens = /\s+|<!--[\s\S]*?-->|<\?xml\s[^?]*\?>|<!DOCTYPE\s+report\s+(?:PUBLIC\s+"[^"]*"\s+"[^"]*"|SYSTEM\s+"[^"]*")\s*>|<\/[A-Za-z][\w:.-]*\s*>|<[A-Za-z][\w:.-]*(?:\s+[^<>]*?)?\s*\/?>/gy;
  let offset = 0;
  let doctype = false;
  while (offset < xml.length) {
    tokens.lastIndex = offset;
    const match = tokens.exec(xml);
    requireValid(match, `битый XML около позиции ${offset}`);
    offset = tokens.lastIndex;
    const token = match[0];
    if (!token.trim() || token.startsWith('<!--') || token.startsWith('<?xml')) continue;
    if (token.startsWith('<!DOCTYPE')) {
      requireValid(!doctype && stack.length === 1 && !document.children.length, 'неверный DOCTYPE');
      doctype = true;
      continue;
    }
    if (token.startsWith('</')) {
      const name = token.slice(2, -1).trim();
      requireValid(stack.length > 1 && stack.at(-1).name === name, `несогласованный закрывающий тег ${name}`);
      stack.pop();
      continue;
    }
    const opening = token.match(/^<([\w:.-]+)([\s\S]*?)(\/?)>$/);
    const node = { name: opening[1], attributes: attributes(opening[2]), children: [] };
    stack.at(-1).children.push(node);
    if (!opening[3]) stack.push(node);
  }
  requireValid(stack.length === 1 && document.children.length === 1 && document.children[0].name === 'report', 'ожидался полный XML с корнем report');
  return document.children[0];
}
function required(node, name, allowEmpty = false) {
  const value = node.attributes[name];
  requireValid(typeof value === 'string' && (allowEmpty || value.length > 0), `нет атрибута ${name} у ${node.name}`);
  return value;
}
function counter(method, type) {
  const matches = method.children.filter(node => node.name === 'counter' && node.attributes.type === type);
  requireValid(matches.length === 1, `у метода ${required(method, 'name')} нужен один счётчик ${type}`);
  return ['missed', 'covered'].map(name => {
    const value = required(matches[0], name);
    requireValid(/^\d+$/.test(value) && Number.isSafeInteger(Number(value)), `неверный ${type}.${name}`);
    return Number(value);
  });
}
function measure(method, className) {
  const [missed, covered] = counter(method, 'INSTRUCTION');
  const complexity = counter(method, 'COMPLEXITY');
  const cc = complexity[0] + complexity[1];
  requireValid(Number.isSafeInteger(missed + covered) && missed + covered > 0 && Number.isSafeInteger(cc) && cc > 0, 'неверная сумма счётчиков метода');
  const coverage = covered / (missed + covered);
  return {
    method: `${className}#${required(method, 'name')}${required(method, 'desc')}`,
    cc, coverage, crap: cc ** 2 * (1 - coverage) ** 3 + cc,
  };
}
function methods(report) {
  const result = [];
  for (const node of report.children) {
    if (node.name === 'group') result.push(...methods(node));
    if (node.name !== 'package' || !required(node, 'name', true).split('/').includes('domain')) continue;
    for (const klass of node.children.filter(child => child.name === 'class')) {
      for (const method of klass.children.filter(child => child.name === 'method')) {
        result.push(measure(method, required(klass, 'name')));
      }
    }
  }
  return result;
}
function table(rows) {
  console.log('Метод\tCC\tПокрытие\tCRAP');
  for (const row of rows) {
    console.log(`${row.method}\t${row.cc}\t${Number((row.coverage * 100).toFixed(2))}%\t${Number(row.crap.toFixed(4))}`);
  }
}
try {
  requireValid(process.argv.length === 3, 'использование: node tools/crap.mjs <jacoco.xml>');
  const rows = methods(parseReport(await readFile(process.argv[2], 'utf8')));
  if (!rows.length) console.log('Нет методов domain — нечего проверять');
  else {
    console.log('Проверенные методы:');
    table(rows);
    const failures = rows.filter(row => row.crap > 10);
    if (failures.length) {
      console.log('\nCRAP > 10:');
      table(failures);
      process.exitCode = 1;
    }
  }
} catch (error) {
  console.error(`Не удалось проверить отчёт JaCoCo: ${error.message}`);
  process.exitCode = 2;
}
