package com.nuvio.tv.core.server

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.R
import com.nuvio.tv.core.ai.AiProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

internal object AiAssistantWebPage {

    fun html(rawContext: Context?): String {
        val context = rawContext?.let { base ->
            val tag = base.getSharedPreferences("app_locale", Context.MODE_PRIVATE).getString("locale_tag", null)
            if (!tag.isNullOrEmpty()) {
                val config = Configuration(base.resources.configuration)
                config.setLocale(Locale.forLanguageTag(tag))
                base.createConfigurationContext(config)
            } else {
                base
            }
        }
        fun text(id: Int, fallback: String): String = context?.getString(id) ?: fallback

        val appName = text(R.string.app_name, "NuvioTV")
        val title = text(R.string.web_ai_title, "AI assistant")
        val subtitle = text(R.string.web_ai_subtitle, "Choose the provider, paste the API key and tap Save. It goes straight to the TV.")
        val providerLabel = text(R.string.web_ai_provider, "Provider")
        val keyLabel = text(R.string.web_ai_key, "API key")
        val keyPlaceholder = text(R.string.web_ai_key_placeholder, "Paste the key here")
        val getKey = text(R.string.web_ai_get_key, "Get a key at")
        val free = text(R.string.ai_assistant_free, "free")
        val save = text(R.string.web_ai_save, "Save")
        val saved = text(R.string.web_ai_saved, "Key sent to the TV. You can pick the model on the TV.")
        val errorEmpty = text(R.string.web_ai_error_empty, "Paste the key.")
        val errorSend = text(R.string.web_ai_error, "Could not send. Is the TV still showing the QR code?")
        val hasKey = text(R.string.web_ai_has_key, "This TV already has a key for this provider; saving replaces it.")

        val providers = AiProvider.entries.joinToString(",") { provider ->
            val label = if (provider == AiProvider.Gemini) "${provider.displayName} ($free)" else provider.displayName
            """{"id":${js(provider.id)},"label":${js(label)},"keyUrl":${js(provider.keyUrl)}}"""
        }

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(appName)} - ${esc(title)}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#000;color:#fff;
display:flex;justify-content:center;min-height:100vh;padding:16px}
.card{max-width:560px;width:100%;padding:32px 0}
h1{font-size:20px;font-weight:600;margin-bottom:4px}
.subtitle{font-size:13px;color:rgba(255,255,255,0.55);margin-bottom:24px}
label{display:block;font-size:12px;font-weight:500;color:rgba(255,255,255,0.4);letter-spacing:0.08em;
text-transform:uppercase;margin:20px 0 8px}
.providers{display:flex;flex-wrap:wrap;gap:8px}
.providers button{padding:10px 14px;border-radius:12px;border:1px solid rgba(255,255,255,0.18);
background:transparent;color:#fff;font-size:14px}
.providers button.on{background:#fff;color:#000;border-color:#fff}
input{width:100%;padding:14px 16px;border-radius:14px;border:1px solid rgba(255,255,255,0.18);
background:transparent;color:#fff;font-size:15px}
.hint{font-size:12px;color:rgba(255,255,255,0.45);margin-top:8px}
.save{width:100%;margin-top:24px;padding:14px;border-radius:14px;border:0;background:#fff;color:#000;
font-size:16px;font-weight:600}
.status{margin-top:16px;font-size:14px;min-height:20px}
.ok{color:#7ee787}.err{color:#ff7b72}
</style>
</head>
<body>
<div class="card">
<h1>${esc(title)}</h1>
<p class="subtitle">${esc(subtitle)}</p>
<label>${esc(providerLabel)}</label>
<div class="providers" id="providers"></div>
<label for="key">${esc(keyLabel)}</label>
<input id="key" type="password" autocomplete="off" autocapitalize="off" spellcheck="false"
 placeholder="${esc(keyPlaceholder)}">
<p class="hint" id="hint"></p>
<button class="save" id="save">${esc(save)}</button>
<p class="status" id="status"></p>
</div>
<script>
const PROVIDERS=[$providers];
const T={getKey:${js(getKey)},saved:${js(saved)},errorEmpty:${js(errorEmpty)},errorSend:${js(errorSend)},hasKey:${js(hasKey)}};
const TOKEN=new URLSearchParams(location.search).get('t')||'';
let selected=PROVIDERS[0].id, configured=[];
const box=document.getElementById('providers'), hint=document.getElementById('hint'), status=document.getElementById('status');
function render(){
  box.innerHTML='';
  PROVIDERS.forEach(p=>{
    const b=document.createElement('button');
    b.textContent=p.label; if(p.id===selected) b.className='on';
    b.onclick=()=>{selected=p.id; render();};
    box.appendChild(b);
  });
  const p=PROVIDERS.find(x=>x.id===selected);
  hint.textContent=T.getKey+' '+p.keyUrl+(configured.includes(selected)?' — '+T.hasKey:'');
}
fetch('/api/state?t='+encodeURIComponent(TOKEN)).then(r=>r.json()).then(s=>{
  if(PROVIDERS.some(p=>p.id===s.provider)) selected=s.provider;
  configured=s.configured||[]; render();
}).catch(render);
document.getElementById('save').onclick=()=>{
  const key=document.getElementById('key').value.trim();
  if(!key){status.className='status err'; status.textContent=T.errorEmpty; return;}
  fetch('/api/key?t='+encodeURIComponent(TOKEN),{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({provider:selected,apiKey:key})})
  .then(r=>{ if(!r.ok) throw new Error(); status.className='status ok'; status.textContent=T.saved;
    document.getElementById('key').value=''; })
  .catch(()=>{ status.className='status err'; status.textContent=T.errorSend; });
};
render();
</script>
</body>
</html>
""".trimIndent()
    }

    private fun js(value: String): String = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
