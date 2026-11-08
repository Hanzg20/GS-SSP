import React, { useRef, useState } from 'react'

/**
 * GoldSky's AI look: everything Beaver says or proposes sits in this card --
 * a gold-to-sky edge, Beaver's badge in the corner, and where it came from
 * at the bottom. What the store decided (menu, prices, the order's status)
 * never wears it, so a customer can always tell the two apart.
 */
export function AiCard({ children, source, mood = 'idle', className = '' }) {
  return (
    <div className={`ai-card ${className}`}>
      <span className="ai-badge" aria-hidden="true"><img src={`/beaver/${mood}.webp`} alt="" draggable="false" /></span>
      {children}
      {source && <p className="ai-source">✦ {source}</p>}
    </div>
  )
}

/**
 * Tap to talk, tap to stop (30 s max). [onAudio] gets the recording as
 * base64 + mime type; [onFail] when the phone won't give us the microphone.
 */
export function useVoice(onAudio, onFail) {
  const [recording, setRecording] = useState(false)
  const recRef = useRef(null)
  const toggle = async () => {
    if (recRef.current) { recRef.current.stop(); return }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const rec = new MediaRecorder(stream)
      const chunks = []
      rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data) }
      rec.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop())
        recRef.current = null
        setRecording(false)
        const blob = new Blob(chunks, { type: rec.mimeType || 'audio/webm' })
        if (!blob.size) return
        const b64 = await new Promise((ok) => { const fr = new FileReader(); fr.onload = () => ok(String(fr.result).split(',')[1]); fr.readAsDataURL(blob) })
        onAudio(b64, blob.type)
      }
      rec.start()
      recRef.current = rec
      setRecording(true)
      setTimeout(() => { if (rec.state === 'recording') rec.stop() }, 30000)
    } catch {
      onFail()
    }
  }
  return { recording, toggle }
}
