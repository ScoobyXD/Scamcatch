import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const deps = 'C:/Users/Jonat/.cache/codex-runtimes/codex-primary-runtime/dependencies';
const skill = 'C:/Users/Jonat/.codex/plugins/cache/openai-primary-runtime/presentations/26.1007.11041/skills/presentations';
const workspaceDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const tempDir = path.join(workspaceDir, '.deck-build');
const output = path.join(workspaceDir, 'output');
const { Presentation, PresentationFile } = await import(pathToFileURL(path.join(deps, 'node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs')).href);
const { finalizePresentation } = await import(pathToFileURL(path.join(skill, 'container_tools/artifact_tool_utils.mjs')).href);
const presentation = Presentation.create({ slideSize: { width: 1280, height: 720 } });
const C = { navy: '#102A43', blue: '#2563EB', pale: '#DCEBFF', paper: '#FAFCFF', ink: '#102A43', mute: '#52687A', white: '#FFFFFF', amber: '#9D5D0B' };
const refs = {
  ftc: 'https://www.ftc.gov/news-events/news/press-releases/2025/12/ftc-issues-annual-report-congress-agencys-actions-protect-older-adults',
  census: 'https://www.census.gov/newsroom/press-releases/2025/older-adults-outnumber-children.html',
  carefull: 'https://getcarefull.com/',
  zora: 'https://www.zorasafe.com/',
  form: 'https://forms.monday.com/forms/embed/4fdec6d030dbdccc53e83ed2b7c1df6e',
};
function text(slide, value, x, y, w, h, size=28, color=C.ink, bold=false) {
  const shape=slide.shapes.add({geometry:'textbox',position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
  shape.text=value;
  shape.text.style={typeface:'Arial',fontSize:size,color,bold,insets:0,autoFit:'none',wrap:'square',verticalAlignment:'top'};
  return shape;
}
function slide(title, dark=false, note='') {
  const s=presentation.slides.add();
  s.background.fill=dark?C.navy:C.paper;
  const color=dark?C.white:C.ink;
  if(title) text(s,title,72,55,1136,100,46,color,true);
  text(s,'ScamCatch  /  Application draft  /  October 8, 2026',72,665,1040,25,16,dark?C.pale:C.mute);
  text(s,String(presentation.slides.items.length).padStart(2,'0'),1160,663,45,27,17,dark?C.pale:C.mute);
  s.speakerNotes.text=note;
  return s;
}
function row(s,n,title,detail,y,{dark=false,w=1015}={}) {
  text(s,String(n).padStart(2,'0'),72,y,65,48,32,dark?C.pale:C.blue,true);
  text(s,title,159,y,w,41,29,dark?C.white:C.ink,true);
  text(s,detail,159,y+45,w,70,25,dark?C.pale:C.mute);
}

{
 const s=slide('',true,'Company and founder information: user statements and official California records verified October 8, 2026. MIRAE OPUS LLC, SOS entity B20250299103, formed September 15, 2025. SOS status Active. SOS, FTB, Agent and VCFCF standing Good. Official evidence is in application/scamcatch/company-evidence. Application eligibility under the incorporated-organization wording remains unresolved. The product is a development prototype. Bank integration and escalation are proposed. Website: https://scamcatch.io. Domain purchased October 8, 2026. The site uses AWS hosting.');
 text(s,'ScamCatch',72,116,1090,107,88,C.white,true);
 text(s,'A voice conversation when\nsomeone may be facing a scam',76,260,1110,139,45,C.pale);
 text(s,'A product of Mirae Opus LLC',76,459,1020,39,28,C.white);
 text(s,'Jonathan Kim, Founder',76,510,1020,37,25,C.pale);
 text(s,'Development prototype. scamcatch.io',76,576,1040,29,21,C.pale);
}
{
 const s=slide('The cost of a missed pause',false,'Source: '+refs.ftc+'\nFTC release published December 1, 2025, reporting Consumer Sentinel complaints for 2024. Ages 60+. Reported losses increased from about $600M in 2020 to $2.4B in 2024. These are reported losses, not all losses, ScamCatch results, or a revenue market. Founder observation: victim interviews motivated the idea that a timely conversation could support independent verification. This hypothesis is unproven.');
 text(s,'$2.4B',72,170,550,150,114,C.blue,true);
 text(s,'Fraud losses reported by\nUS adults age 60+ in 2024',78,330,570,86,31,C.ink);
 text(s,'About four times the\nreported amount in 2020',78,443,560,88,28,C.mute);
 text(s,'Our hypothesis',740,180,460,47,32,C.ink,true);
 text(s,'A calm voice conversation could help someone pause and verify a request before sending money.',740,258,445,186,31,C.ink);
 text(s,'We need to measure whether that changes outcomes.',740,470,445,86,25,C.mute);
 text(s,'Source: FTC, Protecting Older Consumers, 2024–2025. Reported losses only.',78,603,1100,29,18,C.mute);
}
{
 const s=slide('The proposed intervention',false,'Current evidence: repository README.md and docs/voice.md, inspected October 8, 2026. The Android development build captures visible text with consent, flags a check, and supports an automatic voice warning after prerequisites. No live session was tested for this application. Planned features: bank payment signal, financial-institution escalation, trusted-contact alerts. Product must not claim it blocked a payment.');
 row(s,1,'A bank flags a risky payment attempt','Planned integration. The signal must arrive before the payment completes.',161);
 row(s,2,'ScamCatch starts a voice conversation','It asks the person to pause and explain the situation.',277);
 row(s,3,'The person verifies the request independently','Proposed next steps include a known bank number or a chosen trusted contact.',393);
 text(s,'Today: Android development prototype with a voice warning after a flagged screen check.',72,558,1136,64,25,C.blue,true);
}
{
 const s=slide('Target users and buyers',true,'Source: founder-input-needed.txt, completed by the founder. Target audiences: seniors, banks, credit unions, elderly homes, and children of elderly parents. The slide uses older adults and senior living homes as plain descriptions. No pilot, customer, contract, commitment, or partnership exists according to the founder. The first buyer, channel, price and budget need selection or validation. The initial transfer use case is a proposal.');
 row(s,1,'Users: older adults','Voice support during a suspicious payment request.',166,{dark:true});
 row(s,2,'Potential buyers: families and institutions','Older adults and their adult children. Banks, credit unions and senior living homes.',292,{dark:true});
 row(s,3,'Initial use case: an urgent transfer','Start with an unusual payment to a new recipient.',418,{dark:true});
 text(s,'The first buyer, distribution channel and budget still need validation.',159,572,1000,57,24,C.pale);
}
{
 const s=slide('Market context and sizing',false,'Source: '+refs.census+'\nCensus release dated June 26, 2025. Vintage 2024 Population Estimates: 61.2M US residents age 65+, 18.0% of the population. This count is context and is not the entire target segment (age 50+), the number of paying customers, a serviceable market, or a revenue TAM. Revenue market size must follow validated institutions, reachable users, and pricing. No market revenue estimate is asserted.');
 text(s,'61.2M',72,171,610,134,108,C.blue,true);
 text(s,'US adults age 65+ in 2024',78,332,610,49,31,C.ink);
 text(s,'18% of the US population',78,400,610,50,29,C.mute);
 text(s,'Revenue sizing',740,183,465,48,32,C.ink,true);
 text(s,'1. Select a reachable institution segment.\n\n2. Validate covered users and pricing.\n\n3. Calculate an annual revenue opportunity.',740,262,451,260,26,C.mute);
 text(s,'Population context only. Revenue market estimate pending.',78,552,1090,50,27,C.ink,true);
 text(s,'Source: US Census Bureau, Vintage 2024 Population Estimates, June 26, 2025.',78,611,1100,27,18,C.mute);
}
{
 const s=slide('Existing tools and our proposed focus',false,'Sources accessed October 8, 2026: '+refs.carefull+' and '+refs.zora+'\nCarefull advertises account monitoring, alerts and view-only trusted-contact support. ZoraSafe advertises suspicious-content checking and explanations with next steps. This is a brief comparison of advertised focus. It does not claim either competitor lacks voice support or bank integrations. ScamCatch status comes from project documentation and founder intent. Its differentiation remains a hypothesis.');
 const values=[['Product','Current or proposed focus'],['Carefull','Financial monitoring and alerts.\nView-only support for trusted contacts.'],['ZoraSafe','Suspicious-content checks.\nRisk explanations and next steps.'],['ScamCatch','Proposed voice conversation after a bank signal.\nAndroid voice prototype exists in development.']];
 const table=s.tables.add({rows:4,columns:2,left:72,top:183,width:1136,height:345,columnWidths:[230,906],values});
 table.borders.assign({fill:'#CCD9E5',width:1,style:'solid'});
 table.cells.block({row:0,column:0,rowCount:4,columnCount:2}).assign({fill:C.paper,textStyle:{typeface:'Arial',fontSize:25,color:C.ink},margins:{left:20,right:20,top:17,bottom:13}});
 table.cells.block({row:0,column:0,rowCount:1,columnCount:2}).assign({fill:C.navy,textStyle:{typeface:'Arial',fontSize:25,bold:true,color:C.white}});
 text(s,'The pilot must establish whether the conversation improves decisions.',72,564,1136,72,29,C.blue,true);
}
{
 const s=slide('Prototype evidence and traction',false,'Evidence: C:/Users/Jonat/antiscam/README.md and docs/voice.md, inspected October 8, 2026. Documentation supports development implementation. It does not establish production readiness, a successful live session in this preparation task, accuracy or prevented losses. Source: completed founder-input-needed.txt. Founder confirms zero users, zero formal research, zero pilots, zero contracts and zero customer commitments. Watching victim interviews is informal observation, not formal customer research. No validated performance or outcome results were supplied.');
 text(s,'Implemented in development',72,169,550,45,30,C.ink,true);
 text(s,'1. Android screen checks with consent.\n\n2. A warning after a flagged check.\n\n3. An AI voice conversation after setup.',72,244,551,270,28,C.mute);
 text(s,'Early development',715,169,490,47,30,C.ink,true);
 text(s,'1. 0 users and 0 formal research studies.\n\n2. 0 pilots and 0 contracts.\n\n3. 0 customer commitments.',715,244,489,270,28,C.mute);
 text(s,'Bank triggers and alerts to institutions or trusted contacts remain planned.',72,568,1136,66,27,C.blue,true);
}
{
 const s=slide('Proposed revenue model',true,'The founder lists older adults, adult children, banks, credit unions and senior living homes as target buyers or users. The existing institutional licensing strategy remains an unconfirmed proposal. No additional payment model is asserted. The first buyer, pricing and demand still need validation. Source: completed founder-input-needed.txt confirms total revenue, MRR and ARR of $0.');
 text(s,'Institutional licensing',72,172,1120,64,47,C.white,true);
 text(s,'Proposed: institutions pay to offer ScamCatch\nto customers or residents.',72,269,1110,105,34,C.pale);
 text(s,'1. Initial buyer and pricing remain pending.\n\n2. Validate a paid pilot and an annual license with a usage allowance.\n\n3. Set prices after voice cost and support requirements are known.',72,411,1120,198,27,C.pale);
}
{
 const s=slide('Revenue, funding and runway',false,'Source: founder-input-needed.txt and direct founder clarification, October 8, 2026. Total revenue, MRR, ARR and capital raised are $0. The company is not currently fundraising. Founder estimates ongoing runway at the current prototype scope. This is the founder estimate, not audited unlimited financial runway or zero operating costs. Domain registration and AWS hosting have real costs. Changes in scope or costs can change runway. The live application does not directly require cash balance or monthly burn. Required deck subject: '+refs.form);
 const values=[['Required fact','Status'],['Revenue, MRR and ARR','$0 total revenue / $0 MRR / $0 ARR'],['Capital raised to date','$0'],['Currently raising funds?','No'],['Remaining runway','Ongoing at current prototype scope*']];
 const table=s.tables.add({rows:5,columns:2,left:72,top:177,width:1136,height:373,columnWidths:[512,624],values});
 table.borders.assign({fill:'#CCD9E5',width:1,style:'solid'});
 table.cells.block({row:0,column:0,rowCount:5,columnCount:2}).assign({fill:C.paper,textStyle:{typeface:'Arial',fontSize:25,color:C.ink},margins:{left:20,right:20,top:17,bottom:13}});
 table.cells.block({row:0,column:0,rowCount:1,columnCount:2}).assign({fill:C.navy,textStyle:{typeface:'Arial',fontSize:25,bold:true,color:C.white}});
 text(s,'*Founder estimate. Operating costs and scope can change.',72,583,1136,51,27,C.mute);
}
{
 const s=slide('Founder and application goals',true,'Founder identity, Founder title and software-engineer background come from the user and established context. The completed founder-input-needed.txt states Just me for founders, team and advisors. Jonathan Kim is the sole founder, with no additional team members or advisors listed. Direct founder clarification on October 8, 2026 confirms this is his first startup. Official California records verified October 8, 2026: MIRAE OPUS LLC, SOS entity B20250299103, Active, with SOS, FTB, Agent and VCFCF standing Good. Application eligibility remains unresolved. No AARP endorsement or partnership is claimed. The application asks are proposals.');
 text(s,'Jonathan Kim',72,177,625,69,52,C.white,true);
 text(s,'Sole founder and software engineer\nMirae Opus LLC, California',74,264,640,94,30,C.pale);
 text(s,'No other team members or advisors.\n\nThis is Jonathan’s first startup.',74,404,605,118,28,C.pale);
 text(s,'What we seek',780,178,423,51,32,C.white,true);
 text(s,'1. Older-adult research participants.\n\n2. Fraud-specialist feedback.\n\n3. A bank or credit union pilot partner.',780,261,421,276,28,C.pale);
 text(s,'Mission: reduce the money people lose to scams.',74,576,1128,51,29,C.white,true);
}

await fs.mkdir(output,{recursive:true});
await fs.mkdir(path.join(tempDir,'slides'),{recursive:true});
const candidatePath=path.join(tempDir,'scamcatch-candidate.pptx');
await (await PresentationFile.exportPptx(presentation)).save(candidatePath);
await fs.writeFile(path.join(tempDir,'scamcatch-deck.proto.json'),JSON.stringify(presentation.toProto()));
await fs.mkdir(path.join(tempDir,'finalized-founder'),{recursive:true});
const finalPath=path.join(tempDir,'finalized-founder','ScamCatch-application-founder-update-4.pptx');
const result=await finalizePresentation({
 workspaceDir,candidatePath,finalPath,
 explicitTotalSlideCount:10,
 requiredNativeTableOwnerSlides:[6,9],
 pythonExecutable:path.join(deps,'python/python.exe'),
 integrityValidatorPath:path.join(skill,'container_tools/inspect_presentation_package_integrity.py'),
 layoutValidatorPath:path.join(skill,'container_tools/inspect_presentation_layout_geometry.py'),
 layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-bullet-geometry','--validate-heading-fit','--require-native-table-slide','6','--require-native-table-slide','9'],
 fontPolicy:{basis:'design',families:['Arial']},
 verifyArtifactToolImport:true,
 receiptPath:path.join(tempDir,'validation-founder-update-4.json')
});
console.log(JSON.stringify(result));
await fs.copyFile(finalPath,path.join(output,'ScamCatch-application-draft-v2.pptx'));
for(const [i,s] of presentation.slides.items.entries()) {
 if(![8,9].includes(i)) continue;
 const png=await presentation.export({slide:s,format:'png',scale:2});
 await fs.writeFile(path.join(tempDir,'slides',`slide-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await png.arrayBuffer()));
}
console.log('Updated founder facts in the 10-slide PPTX and changed slide previews.');
