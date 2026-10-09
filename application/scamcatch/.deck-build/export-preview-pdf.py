from pathlib import Path
from reportlab.pdfgen import canvas
from pypdf import PdfReader

root = Path(__file__).resolve().parent.parent
output = root / 'output' / 'ScamCatch-application-draft-v2.pdf'
deck = canvas.Canvas(str(output), pagesize=(960, 540))
deck.setTitle('ScamCatch application draft')
deck.setAuthor('Mirae Opus LLC')
deck.setSubject('Draft application deck. Financial and founder facts remain pending.')
for number in range(1, 11):
    deck.drawImage(str(root / '.deck-build' / 'slides' / f'slide-{number:02d}.png'), 0, 0, width=960, height=540)
    deck.showPage()
deck.save()
pdf = PdfReader(output)
assert len(pdf.pages) == 10
print(f'Saved 10-page PDF preview: {output}')
