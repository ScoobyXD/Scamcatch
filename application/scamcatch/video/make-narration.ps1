$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$videoRoot = $PSScriptRoot
$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
$voice.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::Male, [System.Speech.Synthesis.VoiceAge]::Adult, 0, [System.Globalization.CultureInfo]::GetCultureInfo('en-US'))
$voice.Rate = 1
$voice.Volume = 100
$scenes = @(
    "ScamCatch is a voice assistant in development, helping people pause and assess a possible scam.",
    "Our Android prototype can start an AI voice conversation after a screen check flags a possible scam.",
    "Next, we're exploring bank risk signals that could trigger a conversation before a risky payment.",
    "With consent, trusted contacts or fraud specialists could help. The goal is to protect people's financial independence."
)
for ($scene = 0; $scene -lt $scenes.Count; $scene++) {
    $voice.SetOutputToWaveFile((Join-Path $videoRoot ('scene-' + ($scene + 1) + '.wav')))
    $voice.Speak($scenes[$scene])
    $voice.SetOutputToNull()
}
$voice.Dispose()
Write-Output 'Created four narration segments using the installed Windows synthetic voice.'
