const SETTING_DEFS=[
 {key:'scroll',group:'group_reading',label:'scroll_label',desc:'scroll_desc',default:'vertical',options:[['vertical','scroll_vertical'],['horizontal','scroll_horizontal']]},
 {key:'resume',group:'group_reading',label:'resume_label',desc:'resume_desc',default:'last',options:[['last','resume_last'],['start','resume_start']]},
 {key:'marks',group:'group_reading',label:'marks_label',desc:'marks_desc',default:'once',options:[['once','marks_once'],['repeat','marks_repeat']]},
 {key:'awake',group:'group_reading',label:'awake_label',desc:'awake_desc',default:'more',options:[['more','awake_more'],['always','awake_always'],['system','awake_system']]},
 {key:'rotation',group:'group_display',label:'rotation_label',desc:'rotation_desc',default:'phone',options:[['phone','rotation_phone'],['lock','rotation_lock']]},
 {key:'theme',group:'group_display',label:'theme_label',desc:'theme_desc',default:'day',options:[['day','theme_day'],['night','theme_dark']]},
 {key:'language',group:'group_app',label:'language_label',desc:'language_desc',default:'he',options:[['he','language_he'],['en','language_en']]}
];
const settingsCopies=new Map(copyCatalog.entries.map(entry=>[entry.id,entry]));
let openSetting=null,settingOpener=null;
function settingsText(id,language=interfaceLanguage){const entry=settingsCopies.get(id);return entry?entry[language]:id;}
function copyNode(tag,className,id){const text=settingsText(id),el=node(tag,className,text);el.dataset.copy=id;if(id.endsWith('_desc'))el.hidden=!text.trim();return el;}
function defaultSettingsPreferences(){return Object.fromEntries(SETTING_DEFS.filter(def=>def.key!=='theme').map(def=>[def.key,def.default]));}
function restoreSettingsPreferences(saved){if(!saved||typeof saved!=='object')return;for(const def of SETTING_DEFS){if(def.key!=='theme'&&def.options.some(option=>option[0]===saved[def.key]))state.preferences[def.key]=saved[def.key];}}
function settingValue(def){return def.key==='theme'?state.theme:state.preferences[def.key];}
function settingsScreen(id){
 const screen=node('section','app-view settings-screen');screen.dataset.screen='settings';screen.hidden=true;
 const bar=toolbar(id,false);const title=bar.querySelector('.heading strong');title.textContent=settingsText('settings_title');title.dataset.copy='settings_title';
 const back=node('button','icon settings-back');back.innerHTML=icon('back',id);back.setAttribute('aria-label',settingsText('settings_back'));back.addEventListener('click',()=>showView(settingsReturnView));bar.lastElementChild.replaceWith(back);screen.append(bar);
 const list=node('div','settings-list');
 for(const group of ['group_reading','group_display','group_app']){
  list.append(copyNode('h3','settings-group-title',group));const card=node('div','settings-group');
  for(const def of SETTING_DEFS.filter(def=>def.group===group)){
   const button=node('button','setting-row');button.dataset.setting=def.key;
   const text=node('span','setting-text');text.append(copyNode('span','setting-label',def.label),node('span','setting-value',''));const chevron=node('span','');chevron.innerHTML='<svg class="settings-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7"/></svg>';button.append(text,chevron);
   button.addEventListener('click',()=>openSettingSheet(def,button));card.append(button);
  }
  list.append(card);
 }
 list.append(copyNode('p','settings-footer','settings_footer'));screen.append(list);
 const shade=node('div','settings-shade');shade.hidden=true;shade.addEventListener('click',event=>{if(event.target===shade)closeSettingSheet();});screen.append(shade);return screen;
}
function refreshSettingsValues(){for(const row of document.querySelectorAll('.setting-row')){const def=SETTING_DEFS.find(def=>def.key===row.dataset.setting);const option=def.options.find(option=>option[0]===settingValue(def));row.querySelector('.setting-value').textContent=option?settingsText(option[1]):'';}}
function closeSettingSheet(){const shade=document.querySelector('.settings-shade');shade.hidden=true;shade.replaceChildren();openSetting=null;settingOpener?.focus({preventScroll:true});}
function openSettingSheet(def,opener){
 openSetting=def;settingOpener=opener;const shade=document.querySelector('.settings-shade');shade.replaceChildren();shade.hidden=false;
 const sheet=node('section','options-sheet');sheet.setAttribute('role','dialog');sheet.setAttribute('aria-modal','true');sheet.setAttribute('aria-labelledby','sheet-title');
 const header=node('div','sheet-header'),title=copyNode('h3','sheet-title',def.label);title.id='sheet-title';
 const close=node('button','icon');close.setAttribute('aria-label',settingsText('settings_close'));close.innerHTML='<svg class="symbol" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg>';close.addEventListener('click',closeSettingSheet);header.append(title,close);sheet.append(header,copyNode('p','sheet-desc',def.desc));
 const options=node('div','sheet-options');options.setAttribute('role','radiogroup');options.setAttribute('aria-labelledby','sheet-title');
 for(const option of def.options){
  const button=node('button','sheet-option');button.dataset.settingValue=option[0];button.setAttribute('role','radio');button.setAttribute('aria-checked',String(settingValue(def)===option[0]));button.append(node('span','sheet-radio'),copyNode('span','',option[1]));
  button.addEventListener('click',()=>{if(def.key==='theme')setTheme(option[0]);else{state.preferences[def.key]=option[0];refreshSettingsCopy();update();}closeSettingSheet();});options.append(button);
 }
 sheet.append(options);shade.append(sheet);
 sheet.addEventListener('keydown',event=>{if(event.key==='Escape'){event.preventDefault();closeSettingSheet();}else if(event.key==='Tab'){const buttons=Array.from(sheet.querySelectorAll('button'));const first=buttons[0],last=buttons.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}}});
 options.querySelector('[aria-checked=true]')?.focus({preventScroll:true});
}
function refreshSettingsCopy(){
 interfaceLanguage=state.preferences.language||'he';
 for(const app of document.querySelectorAll('.app'))app.dir=interfaceLanguage==='en'?'ltr':'rtl';
 for(const el of document.querySelectorAll('[data-copy]')){const text=settingsText(el.dataset.copy);el.textContent=text;if(el.dataset.copy.endsWith('_desc'))el.hidden=!text.trim();}
 for(const back of document.querySelectorAll('.settings-back'))back.setAttribute('aria-label',settingsText('settings_back'));
 for(const app of document.querySelectorAll('.app')){
  app.querySelector('.library .heading strong').textContent=interfaceLanguage==='en'?'Documents':'מסמכים';
  app.querySelector('.library .primary span').textContent=interfaceLanguage==='en'?'Open PDF':'פתיחת PDF';
  app.querySelector('.sort span').textContent=interfaceLanguage==='en'?'Newest received first':'מהחדש לישן לפי קבלה';
  for(const title of app.querySelectorAll('.heading strong,.doc-title'))title.dir='auto';
  for(const button of app.querySelectorAll('[data-app-action=settings]'))button.setAttribute('aria-label',settingsText('settings_title'));
  for(const button of app.querySelectorAll('[data-app-action=back-library]'))button.setAttribute('aria-label',interfaceLanguage==='en'?'Back to documents':'חזרה לרשימה');
  const receivedNames={'היום':'Today','אתמול':'Yesterday','לפני יומיים':'Two days ago'};
  for(const row of app.querySelectorAll('.file')){
   const doc=documents.find(doc=>doc.id===row.dataset.doc);
   row.querySelector('.metadata').textContent=interfaceLanguage==='en'?receivedNames[doc.received]+' · '+doc.pages+' pages':doc.received+' · '+doc.pages+' עמודים';
   const pageNumber=doc.position.match(/\d+/)?.[0];
   row.querySelector('.position').textContent=interfaceLanguage==='en'?(pageNumber?'Continue from page '+pageNumber:'Not read yet'):doc.position;
  }
  app.querySelector('.reader .heading small').textContent=interfaceLanguage==='en'?'Page 12 of 84':'עמוד 12 מתוך 84';
  const toolNames=interfaceLanguage==='en'?['Read','Highlight','Pen','Bookmark']:['קריאה','מרקר','עט','סימנייה'];
  app.querySelectorAll('.tool span').forEach((span,index)=>span.textContent=toolNames[index]);
 }
 document.querySelectorAll('[data-theme-choice]').forEach(button=>button.textContent=settingsText(button.dataset.themeChoice==='day'?'theme_day':'theme_dark','he'));
 refreshSettingsValues();
 if(preferenceConfig.dayNight)setTheme(state.theme);
}
function copyGroup(id){if(/^(scroll|resume|marks|awake)_/.test(id))return 'reading';if(/^(rotation|theme)_/.test(id))return 'display';if(/^language_/.test(id))return 'interface';return 'general';}
function settingsCopyDraft(){return {version:copyCatalog.version,status:'draft',entries:copyCatalog.entries.map(entry=>({...entry})),exported_at:new Date().toISOString()};}
function showCopyTransfer(){const text=JSON.stringify(settingsCopyDraft(),null,2);document.getElementById('copy-transfer').hidden=false;document.getElementById('copy-transfer-text').value=text;return text;}
function selectCopyTransfer(){const field=document.getElementById('copy-transfer-text');field.focus();field.select();}
function initializeCopyEditor(){
 const key=storageKey+'-copy-draft';
 try{const saved=JSON.parse(localStorage.getItem(key)||'null');if(saved?.version===copyCatalog.version&&Array.isArray(saved.entries)){for(const entry of saved.entries){const target=settingsCopies.get(entry.id);if(target){if(typeof entry.he==='string')target.he=entry.he;if(typeof entry.en==='string')target.en=entry.en;}}}}catch{}
 const rows=document.getElementById('copy-rows');
 for(const entry of copyCatalog.entries){
  const row=node('section','copy-row');row.dataset.copyGroup=copyGroup(entry.id);row.dataset.copyId=entry.id;
  for(const language of ['he','en']){
   const field=node('div','');const label=node('label','',language==='he'?'עברית':'אנגלית');const input=node('input','');input.id='copy-'+entry.id+'-'+language;input.value=entry[language];input.dir=language==='he'?'rtl':'ltr';label.htmlFor=input.id;
   input.addEventListener('input',()=>{entry[language]=input.value;copyCatalog.status='draft';refreshSettingsCopy();try{localStorage.setItem(key,JSON.stringify(copyCatalog));}catch{}if(!document.getElementById('copy-transfer').hidden)showCopyTransfer();document.getElementById('copy-progress').textContent='התיקון מופיע בדוגמה. כל הנוסחים עדיין טיוטה ולא אושרו.';});field.append(label,input);row.append(field);
  }
  const meaning=node('div','');meaning.append(node('p','copy-meaning',entry.meaning));if(entry.id==='theme_warm')meaning.append(node('span','copy-note','אפשרות עתידית; אינה מופיעה במסך הזה.'));row.append(meaning);rows.append(row);
 }
 function filter(group){document.querySelectorAll('.copy-row').forEach(row=>row.hidden=row.dataset.copyGroup!==group);document.querySelectorAll('[data-copy-filter]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.copyFilter===group)));}
 document.querySelectorAll('[data-copy-filter]').forEach(button=>button.addEventListener('click',()=>filter(button.dataset.copyFilter)));
 filter('general');
 document.getElementById('copy-to-chat').addEventListener('click',async()=>{const text=showCopyTransfer();selectCopyTransfer();const status=document.getElementById('copy-progress');try{if(!navigator.clipboard?.writeText)throw new Error('Clipboard unavailable');await navigator.clipboard.writeText(text);status.textContent='התיקונים הועתקו. חזור לשיחה והדבק אותם בהודעה. הנוסחים עדיין טיוטה.';}catch{status.textContent='הטקסט נבחר בתיבה. העתק אותו עם Ctrl+C או דרך תפריט ההעתקה, ואז הדבק בשיחה. הנוסחים עדיין טיוטה.';}});
 document.getElementById('select-copy-text').addEventListener('click',()=>{showCopyTransfer();selectCopyTransfer();document.getElementById('copy-progress').textContent='הטקסט נבחר. העתק אותו והדבק בשיחה. הנוסחים עדיין טיוטה.';});
 document.getElementById('export-copy').addEventListener('click',()=>{const text=showCopyTransfer();const url=URL.createObjectURL(new Blob([text],{type:'application/json;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download='settings-copy-edited.draft.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1500);document.getElementById('copy-progress').textContent='נשלחה בקשת הורדה. אם אינך מוצא את הקובץ, אפשר להעתיק לשיחה את הטקסט שבתיבה. הנוסחים עדיין טיוטה.';});
}
function initializeSettingsReview(){initializeCopyEditor();refreshSettingsCopy();}
