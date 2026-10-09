"""Render the 30-second application explanation from original text and local narration."""
from pathlib import Path
import json
import subprocess

root = Path(__file__).resolve().parent
output = root.parent / 'output'
output.mkdir(exist_ok=True)
durations = [7, 7, 7, 9]
for index, duration in enumerate(durations, 1):
    source = root / f'scene-{index}.wav'
    probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', str(source)]))
    measured = float(probe['format']['duration'])
    if measured + 0.3 >= duration:
        raise ValueError(f'Scene {index} narration does not fit: {measured}')
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', str(source), '-af', f'adelay=200|200,apad,atrim=0:{duration}', '-ar', '48000', '-ac', '1', str(root / f'padded-{index}.wav')], check=True)

concat = root / 'audio-list.txt'
concat.write_text('\n'.join(f"file 'padded-{i}.wav'" for i in range(1, 5)), encoding='utf-8')
subprocess.run(['ffmpeg', '-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', str(concat), '-c:a', 'pcm_s16le', str(root / 'narration.wav')], check=True)

ass = '''[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Brand,Arial,42,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,7,120,120,80,1
Style: Label,Arial,26,&H00FFC5A0,&H00FFFFFF,&H00000000,&H00000000,-1,0,0,0,100,100,3,0,1,0,0,7,120,120,265,1
Style: Title,Arial,98,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,-1,0,0,0,100,100,-1,0,1,0,0,7,120,120,340,1
Style: Body,Arial,42,&H00E4D5C9,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,120,120,615,1
Style: Footer,Arial,23,&H00BBAA99,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,120,120,965,1
Style: Number,Arial,28,&H00FFC5A0,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,1,0,1,0,0,9,120,120,84,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:30.00,Brand,,0,0,0,,ScamCatch.
Dialogue: 0,0:00:00.00,0:00:30.00,Footer,,0,0,0,,MIRAE OPUS LLC  /  IN DEVELOPMENT  /  SYNTHETIC NARRATION
'''
scenes = [
    ('0:00:00.00', '0:00:07.00', '01 / THE PURPOSE', 'A moment to pause.\\NA chance to protect.', 'A voice assistant in development\\Nfor people facing a possible scam.'),
    ('0:00:07.00', '0:00:14.00', '02 / CURRENT PROTOTYPE', 'A screen check.\\NThen a conversation.', 'The Android prototype can start a voice warning\\Nwhen screen analysis flags a possible scam.'),
    ('0:00:14.00', '0:00:21.00', '03 / PLANNED DIRECTION', 'Support before\\Nmoney moves.', 'We are exploring bank risk signals\\Nthat could trigger a check before payment.'),
    ('0:00:21.00', '0:00:30.00', '04 / SUPPORT WITH CONSENT', 'A trusted person.\\NA safer next step.', 'Planned support from trusted contacts or fraud specialists.\\NOur goal: protect financial independence.')
]
for i, (start, end, label, title, body) in enumerate(scenes, 1):
    ass += f'Dialogue: 1,{start},{end},Label,,0,0,0,,{{\\fad(200,200)}}{label}\n'
    ass += f'Dialogue: 1,{start},{end},Title,,0,0,0,,{{\\fad(200,200)}}{title}\n'
    ass += f'Dialogue: 1,{start},{end},Body,,0,0,0,,{{\\fad(200,200)}}{body}\n'
    ass += f'Dialogue: 1,{start},{end},Number,,0,0,0,,{i:02d} / 04\n'
(root / 'titles.ass').write_text(ass, encoding='utf-8')
movie = output / 'ScamCatch-30-second-explainer.mp4'
subprocess.run(['ffmpeg', '-y', '-v', 'error', '-f', 'lavfi', '-i', 'color=c=0x102640:s=1920x1080:r=30:d=30', '-i', str(root / 'narration.wav'), '-vf', 'ass=titles.ass', '-t', '30', '-c:v', 'libx264', '-preset', 'fast', '-crf', '20', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', str(movie)], cwd=root, check=True)
for second in [3, 10, 17, 25]:
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-ss', str(second), '-i', str(movie), '-frames:v', '1', str(root / f'preview-{second}.png')], check=True)
print(json.dumps({'output': str(movie), 'duration_seconds': 30, 'narration': 'Windows synthetic voice'}))
