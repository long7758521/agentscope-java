import React, { useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import {
  getWeixinQrcode,
  getWeixinQrcodeStatus,
  WeixinQrcodeResponse,
} from '../api/channels';

const S: Record<string, React.CSSProperties> = {
  section: {
    background: '#ffffff',
    border: '1px solid #e2e8f0',
    borderRadius: 14,
    padding: '20px 22px',
    marginBottom: 16,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  title: { fontSize: '1.02rem', fontWeight: 600, color: '#0f172a', margin: '0 0 8px' },
  hint: { color: '#64748b', fontSize: '0.9rem', marginBottom: 14, lineHeight: 1.5 },
  btn: {
    padding: '8px 16px',
    fontSize: '0.86rem',
    fontWeight: 500,
    borderRadius: 8,
    cursor: 'pointer',
    border: '1px solid #cbd5e1',
    background: '#ffffff',
    color: '#475569',
  },
  btnPrimary: {
    background: 'linear-gradient(135deg,#07c160 0%,#06ad56 100%)',
    color: '#ffffff',
    border: 'none',
    boxShadow: '0 1px 4px rgba(7,193,96,0.3), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  qrWrap: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: 12,
    marginTop: 16,
    padding: 16,
    background: '#f8fafc',
    borderRadius: 12,
    border: '1px solid #e2e8f0',
  },
  qrImg: {
    width: 220,
    height: 220,
    borderRadius: 8,
    background: '#ffffff',
    border: '1px solid #e2e8f0',
  },
  status: { fontSize: '0.9rem', color: '#475569' },
  warn: { color: '#b45309', fontSize: '0.88rem', marginTop: 8 },
  err: { color: '#dc2626', fontSize: '0.9rem', marginTop: 8 },
  ok: { color: '#16a34a', fontSize: '0.9rem', marginTop: 8 },
};

interface Props {
  channelId: string;
  hasBotToken: boolean;
  started: boolean;
  onLoginSuccess: () => void;
}

function statusLabel(status: string): string {
  switch (status) {
    case 'wait':
    case 'waiting':
      return '等待扫码…';
    case 'scaned':
    case 'scanned':
      return '已扫码，请在手机上确认登录';
    case 'confirmed':
      return '登录成功';
    case 'expired':
      return '二维码已过期，请重新获取';
    default:
      return status || '未知状态';
  }
}

function isHttpUrl(value: string): boolean {
  return value.startsWith('http://') || value.startsWith('https://');
}

async function resolveQrcodeImageSrc(content: string): Promise<string> {
  if (content.startsWith('data:')) {
    return content;
  }
  if (isHttpUrl(content)) {
    return QRCode.toDataURL(content, { width: 220, margin: 1 });
  }
  return `data:image/png;base64,${content}`;
}

export default function WeixinQrLoginPanel({
  channelId,
  hasBotToken,
  started,
  onLoginSuccess,
}: Props) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [warn, setWarn] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [qrcode, setQrcode] = useState<WeixinQrcodeResponse | null>(null);
  const [qrImageSrc, setQrImageSrc] = useState<string | null>(null);
  const [pollStatus, setPollStatus] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const pollFailRef = useRef(0);

  const needsLogin = !hasBotToken || !started;

  function stopPolling() {
    if (pollRef.current != null) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }

  useEffect(() => () => stopPolling(), []);

  useEffect(() => {
    let cancelled = false;
    const content = qrcode?.qrcode_img_content;
    if (!content) {
      setQrImageSrc(null);
      return;
    }
    void resolveQrcodeImageSrc(content)
      .then(src => {
        if (!cancelled) setQrImageSrc(src);
      })
      .catch(e => {
        if (!cancelled) {
          setErr(e instanceof Error ? e.message : String(e));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [qrcode?.qrcode_img_content]);

  async function handleFetchQrcode() {
    setErr(null);
    setWarn(null);
    setInfo(null);
    setPollStatus(null);
    setQrImageSrc(null);
    stopPolling();
    pollFailRef.current = 0;
    setBusy(true);
    try {
      const resp = await getWeixinQrcode(channelId);
      setQrcode(resp);
      if (!resp.qrcode) {
        setErr('QR code response missing qrcode id');
        return;
      }
      const qrcodeId = resp.qrcode;
      pollRef.current = setInterval(() => {
        void (async () => {
          try {
            const st = await getWeixinQrcodeStatus(channelId, qrcodeId);
            pollFailRef.current = 0;
            setWarn(null);
            const status = st.status ?? '';
            setPollStatus(status);
            if (status === 'confirmed') {
              stopPolling();
              setInfo('微信登录成功，channel 已启动。');
              onLoginSuccess();
            } else if (status === 'expired') {
              stopPolling();
              setErr('二维码已过期，请重新获取。');
            }
          } catch (e: unknown) {
            pollFailRef.current += 1;
            if (pollFailRef.current >= 5) {
              stopPolling();
              setErr(e instanceof Error ? e.message : String(e));
            } else {
              setWarn('轮询状态暂时失败，正在重试…');
            }
          }
        })();
      }, 2000);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  if (!needsLogin) {
    return null;
  }

  return (
    <div style={S.section}>
      <h2 style={S.title}>微信扫码登录</h2>
      <p style={S.hint}>
        该 channel 尚未登录或尚未运行。点击下方按钮获取二维码，用微信扫码并在手机上确认登录。
        Token 过期后也可在此重新扫码。
      </p>
      <button
        style={{ ...S.btn, ...S.btnPrimary }}
        onClick={() => void handleFetchQrcode()}
        disabled={busy}
      >
        {busy ? '获取中…' : '获取登录二维码'}
      </button>
      {err && <div style={S.err}>{err}</div>}
      {warn && <div style={S.warn}>{warn}</div>}
      {info && <div style={S.ok}>{info}</div>}
      {qrImageSrc && (
        <div style={S.qrWrap}>
          <img
            style={S.qrImg}
            src={qrImageSrc}
            alt="Weixin login QR code"
          />
          {pollStatus && <div style={S.status}>{statusLabel(pollStatus)}</div>}
          {qrcode?.qrcode_img_content && isHttpUrl(qrcode.qrcode_img_content) && (
            <div style={{ ...S.status, fontSize: '0.82rem', color: '#94a3b8', wordBreak: 'break-all' }}>
              {qrcode.qrcode_img_content}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
