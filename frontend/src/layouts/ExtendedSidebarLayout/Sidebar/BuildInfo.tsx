import { useEffect, useState } from 'react';
import { Box, Link, Tooltip, useTheme } from '@mui/material';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import api from 'src/utils/api';

// Build stamp under the sidebar footer: "Stand 15.11.2026 14:32 · b113619".
//
// The frontend's own stamp is a static file CI writes into the image
// (frontend/Dockerfile); the api's comes from GET /build-info. When both are known and
// differ, the stamp turns into a warning — that is a half-finished deploy, which until
// now looked like an ordinary bug. Fork-only file; see CLAUDE.md "Build stamp".

interface Stamp {
  sha: string;
  time: string;
  repo?: string;
}

export const BUILD_INFO_HEIGHT = 18;

const short = (sha: string) => sha.slice(0, 7);

async function loadOwnStamp(): Promise<Stamp | null> {
  try {
    const response = await fetch('/build-info.json', { cache: 'no-store' });
    // The dev server answers unknown paths with index.html, so check the type.
    if (
      !response.ok ||
      !response.headers.get('content-type')?.includes('json')
    ) {
      return null;
    }
    const stamp: Stamp = await response.json();
    return stamp.sha ? stamp : null;
  } catch {
    return null;
  }
}

async function loadApiStamp(): Promise<Stamp | null> {
  try {
    const stamp = await api.get<Stamp>('build-info');
    return stamp?.sha ? stamp : null;
  } catch {
    return null;
  }
}

function BuildInfo() {
  const { t }: { t: any } = useTranslation();
  const theme = useTheme();
  const [own, setOwn] = useState<Stamp | null>(null);
  const [server, setServer] = useState<Stamp | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let active = true;
    Promise.all([loadOwnStamp(), loadApiStamp()]).then(([o, s]) => {
      if (!active) return;
      setOwn(o);
      setServer(s);
      setLoaded(true);
    });
    return () => {
      active = false;
    };
  }, []);

  if (!loaded) return <Box sx={{ height: BUILD_INFO_HEIGHT }} />;

  const stamp = own ?? server;
  const mismatch = own && server && own.sha !== server.sha;

  const label = stamp
    ? `${t('build_info_label')} ${dayjs(stamp.time).format(
        'DD.MM.YYYY HH:mm'
      )} · ${short(stamp.sha)}`
    : t('build_info_local');

  const tooltip = mismatch
    ? t('build_info_mismatch', {
        frontend: short(own.sha),
        server: short(server.sha)
      })
    : stamp
    ? stamp.sha
    : '';

  const color = mismatch
    ? theme.colors.warning.main
    : theme.sidebar.menuItemIconColor;

  const text = mismatch ? `⚠ ${label}` : label;
  const href =
    stamp && own?.repo
      ? `https://github.com/${own.repo}/commit/${stamp.sha}`
      : undefined;

  return (
    <Box
      sx={{
        height: BUILD_INFO_HEIGHT,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: theme.typography.pxToRem(11),
        color,
        whiteSpace: 'nowrap'
      }}
    >
      <Tooltip title={tooltip} placement="top" arrow>
        {href ? (
          <Link
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            underline="hover"
            sx={{ color: 'inherit' }}
          >
            {text}
          </Link>
        ) : (
          <span>{text}</span>
        )}
      </Tooltip>
    </Box>
  );
}

export default BuildInfo;
