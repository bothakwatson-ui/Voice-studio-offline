"""Voice Studio: free, CPU-friendly vocal cleanup and backing-track demo.

The built-in backing generator is procedural (not AI). Replace
`generate_ai_instrumental` with a provider or self-hosted MusicGen endpoint.
"""
from __future__ import annotations

import io
from typing import Optional

import numpy as np
import noisereduce as nr
import streamlit as st
from pydub import AudioSegment
from scipy.io import wavfile
from scipy.signal import resample_poly
from scipy.ndimage import uniform_filter1d

SAMPLE_RATE = 44100
MAX_SECONDS = 120


def load_wav(wav_bytes: bytes) -> tuple[int, np.ndarray]:
    """Decode recorded/uploaded PCM WAV to float32 mono in [-1, 1]."""
    sample_rate, audio = wavfile.read(io.BytesIO(wav_bytes))
    if audio.ndim == 2:
        audio = audio.astype(np.float32).mean(axis=1)
    if np.issubdtype(audio.dtype, np.integer):
        dtype = np.dtype(audio.dtype)
        if dtype == np.dtype("uint8"):
            audio = (audio.astype(np.float32) - 128.0) / 128.0
        else:
            audio = audio.astype(np.float32) / float(np.iinfo(dtype).max)
    else:
        audio = audio.astype(np.float32)
    if not len(audio):
        raise ValueError("The recording is empty.")
    if len(audio) / sample_rate > MAX_SECONDS:
        raise ValueError(f"Please record at most {MAX_SECONDS} seconds.")
    return sample_rate, np.nan_to_num(audio, nan=0.0, posinf=0.0, neginf=0.0)


def resample_audio(audio: np.ndarray, source_rate: int, target_rate: int) -> np.ndarray:
    if source_rate == target_rate:
        return audio.astype(np.float32)
    from math import gcd
    factor = gcd(source_rate, target_rate)
    return resample_poly(audio, target_rate // factor, source_rate // factor).astype(np.float32)


def wav_bytes(audio: np.ndarray, rate: int = SAMPLE_RATE) -> bytes:
    """Encode floating-point mono samples as a standard 16-bit WAV."""
    data = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
    buffer = io.BytesIO()
    wavfile.write(buffer, rate, data)
    return buffer.getvalue()


def enhance_vocals(raw_wav: bytes) -> bytes:
    """Stationary noise suppression, smooth gate, and gentle peak normalization."""
    rate, x = load_wav(raw_wav)
    # Stationary reduction works best on relatively constant fan/hiss noise.
    clean = nr.reduce_noise(y=x, sr=rate, stationary=True, prop_decrease=0.75)
    # Gate loudness from smoothed absolute envelope to avoid hard clicking.
    window = max(1, int(rate * 0.02))
    envelope = uniform_filter1d(np.abs(clean), size=window)
    gate_threshold = max(0.004, np.percentile(envelope, 65) * 0.15)
    gain = np.clip((envelope - gate_threshold * 0.3) / (gate_threshold * 1.7 + 1e-9), 0.12, 1.0)
    clean = clean * gain
    peak = float(np.max(np.abs(clean)))
    if peak > 1e-6:
        clean = clean * (0.88 / peak)
    return wav_bytes(resample_audio(clean.astype(np.float32), rate, SAMPLE_RATE))


def genre_settings(prompt: str) -> tuple[int, list[int], str]:
    """Map text prompts to a small set of royalty-free synthetic arrangements."""
    p = prompt.lower()
    if any(word in p for word in ("hip hop", "hip-hop", "rap", "trap")):
        return 90, [57, 60, 64, 67], "hiphop"
    if any(word in p for word in ("gospel", "worship", "choir")):
        return 76, [60, 64, 67, 71], "soft"
    if any(word in p for word in ("afro", "dance", "amapiano", "pop")):
        return 112, [57, 61, 64, 69], "dance"
    if any(word in p for word in ("rock", "punk")):
        return 120, [55, 59, 62, 67], "rock"
    return 96, [60, 64, 67, 71], "soft"


def synth_note(frequency: float, seconds: float, rate: int, softness: float = 1.0) -> np.ndarray:
    n = max(1, round(seconds * rate))
    t = np.arange(n, dtype=np.float32) / rate
    attack = np.minimum(1.0, t / 0.025)
    release = np.exp(-t * (3.0 / max(seconds, 0.01)))
    return ((np.sin(2 * np.pi * frequency * t) + 0.18 * np.sin(4 * np.pi * frequency * t))
            * attack * release * 0.16 * softness).astype(np.float32)


def add_clip(destination: np.ndarray, clip: np.ndarray, position: int) -> None:
    if position >= len(destination):
        return
    end = min(len(destination), position + len(clip))
    destination[position:end] += clip[:end - position]


def generate_free_backing(prompt: str, duration_seconds: float) -> bytes:
    """Produce a functional, entirely offline instrumental (NOT an AI model)."""
    duration_seconds = min(MAX_SECONDS, max(1.0, duration_seconds))
    bpm, chord, pattern = genre_settings(prompt)
    count = int(duration_seconds * SAMPLE_RATE)
    song = np.zeros(count, dtype=np.float32)
    beat = 60.0 / bpm
    # Four-chord pattern constructed from MIDI pitches. Approximate accompaniment only;
    # no automatic pitch/key detection or vocal beat alignment is claimed.
    roots = [chord[0], chord[0] - 3, chord[0] - 5, chord[0] - 7]
    rng = np.random.default_rng(732)
    for step in range(int(np.ceil(duration_seconds / beat))):
        start = step * beat
        chord_index = (step // 4) % 4
        root = roots[chord_index]
        # Warm chord pad/arpeggio
        for interval in (0, 4, 7):
            midi = root + 12 + interval
            freq = 440.0 * 2 ** ((midi - 69) / 12)
            note = synth_note(freq, min(beat * 0.95, duration_seconds - start), SAMPLE_RATE, 0.50)
            add_clip(song, note, int(start * SAMPLE_RATE))
        # Bass on the downbeat
        if step % 2 == 0:
            bass_freq = 440 * 2 ** ((root - 12 - 69) / 12)
            add_clip(song, synth_note(bass_freq, beat * 0.9, SAMPLE_RATE, 1.8), int(start * SAMPLE_RATE))
        # Deterministic simple synthesized kick/snare/hat percussion
        if pattern != "soft" or step % 4 == 0:
            drum_len = min(int(0.16 * SAMPLE_RATE), count)
            dt = np.arange(drum_len) / SAMPLE_RATE
            if step % 4 in (0, 2):
                kick = 0.24 * np.sin(2 * np.pi * (60 - 25 * dt) * dt) * np.exp(-35 * dt)
                add_clip(song, kick.astype(np.float32), int(start * SAMPLE_RATE))
            if step % 4 in (1, 3):
                snare = 0.065 * rng.normal(size=drum_len) * np.exp(-35 * dt)
                add_clip(song, snare.astype(np.float32), int(start * SAMPLE_RATE))
            if pattern in ("dance", "hiphop", "rock"):
                hat = 0.02 * rng.normal(size=drum_len) * np.exp(-90 * dt)
                add_clip(song, hat.astype(np.float32), int(start * SAMPLE_RATE))
    # Small fade and clipping protection
    fade = min(count // 2, int(0.15 * SAMPLE_RATE))
    if fade:
        song[:fade] *= np.linspace(0, 1, fade)
        song[-fade:] *= np.linspace(1, 0, fade)
    max_val = float(np.max(np.abs(song)))
    if max_val > 0.85:
        song *= 0.85 / max_val
    return wav_bytes(song)


def generate_ai_instrumental(prompt: str, duration_seconds: float, token: Optional[str] = None) -> bytes:
    """Integration placeholder: return a WAV byte string from a deployed model.

    Example integration outline (only after you have a working endpoint):

        import requests
        response = requests.post(
            os.environ["MUSIC_ENDPOINT_URL"],
            headers={"Authorization": f"Bearer {token}"},
            json={"inputs": prompt, "parameters": {"duration": duration_seconds}},
            timeout=180,
        )
        response.raise_for_status()
        return response.content  # convert to WAV if endpoint gives another format

    IMPORTANT: facebook/musicgen-small is *not* currently available through
    HF's standard hosted Inference Providers. An endpoint requires a compatible
    deployed service and may require paid GPU time. It is not called by default.
    """
    raise NotImplementedError("Connect a deployed text-to-music endpoint in generate_ai_instrumental().")


def mix_tracks(vocal_wav: bytes, backing_wav: bytes, vocal_db: int, backing_db: int) -> AudioSegment:
    voice = AudioSegment.from_file(io.BytesIO(vocal_wav), format="wav")
    backing = AudioSegment.from_file(io.BytesIO(backing_wav), format="wav")
    # Match rate/channel and duration; loop instrumental if a connected model makes a short clip.
    voice = voice.set_frame_rate(SAMPLE_RATE).set_channels(2)
    backing = backing.set_frame_rate(SAMPLE_RATE).set_channels(2)
    if len(backing) == 0:
        raise ValueError("The backing track is empty.")
    while len(backing) < len(voice):
        backing += backing
    backing = backing[:len(voice)] + backing_db
    mix = backing.overlay(voice + vocal_db)
    # Preserve headroom for final export.
    if mix.max_dBFS != float("-inf") and mix.max_dBFS > -1.0:
        mix = mix.apply_gain(-1.0 - mix.max_dBFS)
    return mix


def export_audio(segment: AudioSegment, kind: str) -> bytes:
    buffer = io.BytesIO()
    segment.export(buffer, format=kind, bitrate="192k" if kind == "mp3" else None)
    return buffer.getvalue()


st.set_page_config(page_title="Voice Studio — Free", page_icon="🎙️", layout="wide")
st.title("🎙️ Voice Studio")
st.caption("Record → clean your voice → add a free instrumental → download your song")
st.info("Record in a quiet room, choose a style, then click **Process song**. The built-in backing is a simple synthetic demo, not generative AI or automatic melody matching.")

col1, col2, col3 = st.columns(3, gap="large")
with col1:
    st.subheader("1 · Record")
    recording = st.audio_input("Record your voice", sample_rate=44100)
    upload = st.file_uploader("Or upload a WAV file", type=["wav"])
    source = recording if recording is not None else upload
    if source is not None:
        st.audio(source.getvalue(), format="audio/wav")
with col2:
    st.subheader("2 · Process")
    prompt = st.text_input("Instrumental style", "gentle gospel piano with a steady beat", max_chars=200)
    vocal_volume = st.slider("Vocal volume (dB)", -12, 8, 1)
    beat_volume = st.slider("Backing volume (dB)", -24, 4, -7)
    process = st.button("✨ Process song", type="primary", disabled=source is None)
with col3:
    st.subheader("3 · Download")
    if "final_wav" not in st.session_state:
        st.caption("Your finished song will appear here.")

if process and source is not None:
    try:
        with st.spinner("Removing noise and mixing a backing track..."):
            raw = source.getvalue()
            rate, audio = load_wav(raw)
            duration = len(audio) / rate
            vocal = enhance_vocals(raw)
            instrumental = generate_free_backing(prompt, duration)
            final = mix_tracks(vocal, instrumental, vocal_volume, beat_volume)
            st.session_state["final_wav"] = export_audio(final, "wav")
            try:
                st.session_state["final_mp3"] = export_audio(final, "mp3")
            except Exception:
                # WAV continues working even without FFmpeg/LAME encoder.
                st.session_state["final_mp3"] = None
            st.session_state["enhanced_vocal"] = vocal
        st.success("Your song is ready!")
    except Exception as error:
        st.error(f"Could not process this recording: {error}")

if "final_wav" in st.session_state:
    st.subheader("🎧 Listen to your finished song")
    st.audio(st.session_state["final_wav"], format="audio/wav")
    dl1, dl2 = st.columns(2)
    with dl1:
        st.download_button("⬇️ Download WAV", st.session_state["final_wav"],
                           file_name="my_song.wav", mime="audio/wav")
    with dl2:
        if st.session_state.get("final_mp3"):
            st.download_button("⬇️ Download MP3", st.session_state["final_mp3"],
                               file_name="my_song.mp3", mime="audio/mpeg")
        else:
            st.caption("MP3 unavailable: install FFmpeg. WAV is ready.")
    with st.expander("Download enhanced voice only"):
        st.download_button("Download clean vocal WAV", st.session_state["enhanced_vocal"],
                           file_name="enhanced_voice.wav", mime="audio/wav")

st.divider()
st.caption("Local processing in the app server. Avoid recording sensitive content on public deployments. All project code is open source; optional third-party model weights have their own licenses.")
