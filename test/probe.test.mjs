/*
 * Offline harness for the injected page scripts.
 *
 * Loads assets/js/probe.js and assets/js/stealth.js into a jsdom page that mimics the question
 * pages of the host app, then exercises ping/scan/fill/next and asserts the resulting DOM state.
 *
 * Run:  node test/probe.test.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {JSDOM} from 'jsdom';

const here = path.dirname(fileURLToPath(import.meta.url));
const assets = path.resolve(here, '..', 'app', 'src', 'main', 'assets', 'js');
const probe = fs.readFileSync(path.join(assets, 'probe.js'), 'utf8');
const stealth = fs.readFileSync(path.join(assets, 'stealth.js'), 'utf8');

let failures = 0;
function check(name, condition, detail) {
    if (condition) {
        console.log('  ok   ' + name);
    } else {
        failures++;
        console.log('  FAIL ' + name + (detail === undefined ? '' : ' -> ' + detail));
    }
}

const PAGE = `<!DOCTYPE html><html><body>
<div class="questionLi" id="question881">
  <input type="hidden" name="questionId" value="881"/>
  <input type="hidden" name="type881" value="0"/>
  <h3 class="tit">1.【单选题】下列哪个是 Java 的访问修饰符？</h3>
  <ul>
    <li class="singleChoice" name="A"><span class="num">A</span><input type="radio" name="r881" value="A"/><div class="answerInfo">final</div></li>
    <li class="singleChoice" name="B"><span class="num">B</span><input type="radio" name="r881" value="B"/><div class="answerInfo">static</div></li>
    <li class="singleChoice" name="C"><span class="num">C</span><input type="radio" name="r881" value="C"/><div class="answerInfo">private</div></li>
    <li class="singleChoice" name="D"><span class="num">D</span><input type="radio" name="r881" value="D"/><div class="answerInfo">extends</div></li>
  </ul>
  <input type="hidden" id="answer881" value=""/>
</div>

<div class="questionLi" id="question882">
  <input type="hidden" name="questionId" value="882"/>
  <input type="hidden" name="type882" value="2"/>
  <h3 class="tit">2.【填空题】面向对象的三大特性是____、____、____</h3>
  <input type="hidden" name="blankNum882" value="3"/>
  <textarea id="my-content882-1"></textarea>
  <textarea id="my-content882-2"></textarea>
  <textarea id="my-content882-3"></textarea>
  <input type="hidden" id="answer882" value=""/>
</div>

<div class="questionLi" id="question883">
  <input type="hidden" name="questionId" value="883"/>
  <input type="hidden" name="type883" value="3"/>
  <h3 class="tit">3.【判断题】HTTP 是无状态协议。</h3>
  <ul>
    <li class="trueOrFalse" name="true"><div class="answerInfo">对</div></li>
    <li class="trueOrFalse" name="false"><div class="answerInfo">错</div></li>
  </ul>
  <input type="hidden" id="answer883" value=""/>
</div>

<div class="questionLi" id="question884">
  <input type="hidden" name="questionId" value="884"/>
  <input type="hidden" name="type884" value="1"/>
  <h3 class="tit">4.【多选题】以下属于 JVM 垃圾回收器的有？</h3>
  <ul>
    <li class="mulChoice" name="A"><input type="checkbox" name="c884" value="A"/><div class="answerInfo">G1</div></li>
    <li class="mulChoice" name="B"><input type="checkbox" name="c884" value="B"/><div class="answerInfo">CMS</div></li>
    <li class="mulChoice" name="C"><input type="checkbox" name="c884" value="C"/><div class="answerInfo">ZGC</div></li>
    <li class="mulChoice" name="D"><input type="checkbox" name="c884" value="D"/><div class="answerInfo">javac</div></li>
  </ul>
  <input type="hidden" id="answers884" value=""/>
</div>

<div class="questionLi" id="question885">
  <input type="hidden" name="questionId" value="885"/>
  <input type="hidden" name="type885" value="0"/>
  <h3 class="tit">5.【单选题】如图所示，两个同心均匀带电球面之间的电势为：</h3>
  <div class="stem"><img src="http://p.ananas.chaoxing.com/star3/origin/d47af5adab301dc7a006e47c62eb4576.png"/></div>
  <ul>
    <li class="singleChoice" name="A"><div class="answerInfo"><img src="http://p.ananas.chaoxing.com/star3/origin/aaaa1111.png"/></div></li>
    <li class="singleChoice" name="B"><div class="answerInfo"><img src="http://p.ananas.chaoxing.com/star3/origin/bbbb2222.png"/></div></li>
  </ul>
</div>

<a id="nextQuestion" style="display:block">下一题</a>
</body></html>`;

const dom = new JSDOM(PAGE, {url: 'https://mooc1.chaoxing.com/mooc2/work/dohomework?index=1', runScripts: 'outside-only', pretendToBeVisual: true});
const {window} = dom;
const {document} = window;

window.eval(stealth);
window.eval(probe);

console.log('probe: injection');
check('__lumen defined', !!window.__lumen);
check('stealth hides document.hidden', document.hidden === false && document.visibilityState === 'visible');

console.log('probe: ping');
check('ping sees questions', window.__lumen.ping() === 'QUESTIONS', window.__lumen.ping());

console.log('probe: scan');
const scan = JSON.parse(window.__lumen.scan());
check('scan ok', scan.ok === 1, JSON.stringify(scan).slice(0, 200));
check('scan found 5 questions', scan.items.length === 5, scan.items.length);
const byId = Object.fromEntries(scan.items.map(q => [q.qid, q]));
check('q881 stem cleaned', /访问修饰符/.test(byId['881'].stem), byId['881'].stem);
check('q881 type 0', byId['881'].type === 0, byId['881'].type);
check('q881 has 4 options', byId['881'].options.length === 4, byId['881'].options.length);
check('q881 option text', byId['881'].options[2].t === 'private', byId['881'].options[2].t);
check('q882 blanks = 3', byId['882'].blanks === 3, byId['882'].blanks);
check('q883 judge type', byId['883'].type === 3, byId['883'].type);
check('q884 multi type', byId['884'].type === 1, byId['884'].type);
check('text question has no image flag', !byId['881'].img, byId['881'].img);

console.log('probe: picture question + frame box');
check('scan reports a picture question', scan.items.some(q => q.img === 1),
    JSON.stringify(scan.items.map(q => [q.qid, q.img])));
const pic = scan.items.find(q => q.img === 1);
check('picture question keeps an option image src', /^https?:/.test(pic.options[0].src || ''),
    JSON.stringify(pic.options[0]));
// jsdom has no layout engine, so heights are always 0 here; only the shape is asserted.
check('picture question has a page box',
    pic.box && typeof pic.box.h === 'number' && typeof pic.box.vh === 'number' && pic.box.vh > 0,
    JSON.stringify(pic.box));
const frame = JSON.parse(window.__lumen.frame(0));
check('frame reports the first question box',
    frame.qid === '881' && typeof frame.h === 'number' && typeof frame.viewH === 'number',
    JSON.stringify(frame));
check('scrollTo moves the page', window.__lumen.scrollTo(40) !== '-1', 'scrollTo failed');

console.log('probe: fill');
const payload = JSON.stringify({
    answers: [
        {qid: '881', type: 0, answer: 'C'},
        {qid: '882', type: 2, answer: '封装|继承|多态'},
        {qid: '883', type: 3, answer: 'A'},
        {qid: '884', type: 1, answer: 'ABC'}
    ]
});
const filled = JSON.parse(window.__lumen.fill(payload));
check('fill ok', filled.ok === 1, JSON.stringify(filled).slice(0, 200));
check('fill wrote 4', filled.res.length === 4, JSON.stringify(filled.res));
check('single choice checked', document.querySelector('input[value="C"]').checked === true);
check('single answer field', document.getElementById('answer881').value === 'C',
    document.getElementById('answer881').value);
check('blanks written', document.getElementById('my-content882-2').value === '继承',
    document.getElementById('my-content882-2').value);
check('blank answer field', document.getElementById('answer882').value === '封装|继承|多态',
    document.getElementById('answer882').value);
check('judge true picked', document.getElementById('answer883').value === 'A',
    document.getElementById('answer883').value);
check('multi A checked', document.querySelector('input[name="c884"][value="A"]').checked === true);
check('multi C checked', document.querySelector('input[name="c884"][value="C"]').checked === true);
check('multi D unchecked', document.querySelector('input[name="c884"][value="D"]').checked === false);
check('multi answer field', document.getElementById('answers884').value === 'ABC',
    document.getElementById('answers884').value);

console.log('probe: next');
window.getTheNextQuestion = () => { window.__nextCalled = true; };
// jsdom has no layout engine, so offsetParent is always null; fake it like a visible button.
Object.defineProperty(document.getElementById('nextQuestion'), 'offsetParent',
    {value: document.body, configurable: true});
window.__lumen.next();
check('next calls page function', window.__nextCalled === true);

console.log('');
if (failures === 0) {
    console.log('ALL CHECKS PASSED');
    process.exit(0);
} else {
    console.log(failures + ' CHECK(S) FAILED');
    process.exit(1);
}
