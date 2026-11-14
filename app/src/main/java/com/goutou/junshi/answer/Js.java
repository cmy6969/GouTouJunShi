package com.goutou.junshi.answer;

/** Diagnostic JavaScript snippets that are only injected when {@code answer.debug_dump} is on. */
public final class Js {

    private Js() {
    }

    /**
     * Reports the shape of the current question block: which containers exist, which of them holds a
     * {@code questionId} input, the input names present, and the outer HTML of the block (trimmed).
     */
    public static final String DUMP =
            "(function(){try{"
                    + "var ROOTS='.questionWrap,.TiMu,.tiMu,.singleQuesId,.questionLi,.queBox,.mark_item,"
                    + ".questionItem,.exam-item,.pad_question,.subjectDet,.mark_itemBox,.topic-item';"
                    + "var roots=document.querySelectorAll(ROOTS);"
                    + "var o={url:String(location.href).slice(0,180),roots:roots.length};"
                    + "var q=null;"
                    + "for(var i=0;i<roots.length;i++){"
                    + "if(roots[i].querySelector&&roots[i].querySelector('input[name=\"questionId\"]')){q=roots[i];break;}}"
                    + "o.pick=q?('cls='+String(q.className||'')+' id='+String(q.id||'')):'none';"
                    + "var host=q||document.body;"
                    + "o.html=String(host.innerHTML||'').replace(/\\s+/g,' ').slice(0,2600);"
                    + "var ins=[],all=document.querySelectorAll('input,textarea');"
                    + "for(var j=0;j<all.length&&j<40;j++){"
                    + "ins.push(String(all[j].tagName).toLowerCase()+':'+String(all[j].name||all[j].id||'')+':'+String(all[j].type||''));}"
                    + "o.inputs=ins.join(' | ');"
                    + "return JSON.stringify(o);"
                    + "}catch(e){return 'ERR '+String(e&&e.message||e);}})()";
}
