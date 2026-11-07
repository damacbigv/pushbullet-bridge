/**
 * Link Preview Controller
 * Extracts OpenGraph and HTML metadata for rich card link previews
 */

/**
 * In-memory preview cache to avoid redundant network hits
 */
const previewCache = new Map();

export const getLinkPreview = async (req, res) => {
  const targetUrl = req.body?.url || req.query?.url;

  if (!targetUrl || typeof targetUrl !== 'string') {
    return res.status(400).json({ success: false, error: 'URL parameter is required.' });
  }

  let parsedUrl;
  try {
    parsedUrl = new URL(targetUrl);
    if (!['http:', 'https:'].includes(parsedUrl.protocol)) {
      return res.status(400).json({ success: false, error: 'Invalid URL protocol.' });
    }
  } catch (err) {
    return res.status(400).json({ success: false, error: 'Malformed URL.' });
  }

  // Check cache
  const cacheKey = parsedUrl.href;
  if (previewCache.has(cacheKey)) {
    return res.json({ success: true, ...previewCache.get(cacheKey) });
  }

  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 6000);

    const response = await fetch(parsedUrl.href, {
      signal: controller.signal,
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 PushBridge-Bot/3.0',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
      }
    });

    clearTimeout(timeout);

    if (!response.ok) {
      throw new Error(`HTTP ${response.status} ${response.statusText}`);
    }

    const contentType = response.headers.get('content-type') || '';
    if (!contentType.includes('text/html') && !contentType.includes('xml')) {
      // Direct media link
      const preview = {
        url: parsedUrl.href,
        domain: parsedUrl.hostname,
        title: parsedUrl.pathname.split('/').pop() || parsedUrl.hostname,
        description: `Direct ${contentType} resource`,
        image: contentType.startsWith('image/') ? parsedUrl.href : null
      };
      previewCache.set(cacheKey, preview);
      return res.json({ success: true, ...preview });
    }

    // Read only first 80KB to extract HTML <head> metadata efficiently
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let htmlChunk = '';
    let bytesRead = 0;
    const MAX_BYTES = 80 * 1024; // 80KB

    while (bytesRead < MAX_BYTES) {
      const { done, value } = await reader.read();
      if (done) break;
      htmlChunk += decoder.decode(value, { stream: true });
      bytesRead += value.length;
      if (htmlChunk.includes('</head>') || htmlChunk.includes('<body')) {
        break;
      }
    }
    reader.cancel().catch(() => {});

    // Regex extractors for OpenGraph and Standard HTML Metadata
    const extractMeta = (property) => {
      const regex = new RegExp(`<meta\\s+[^>]*(?:property|name)=["'](?:og:)?${property}["'][^>]*content=["']([^"']*)["']`, 'i');
      const altRegex = new RegExp(`<meta\\s+[^>]*content=["']([^"']*)["'][^>]*(?:property|name)=["'](?:og:)?${property}["']`, 'i');
      const match = htmlChunk.match(regex) || htmlChunk.match(altRegex);
      return match ? decodeHtmlEntities(match[1].trim()) : null;
    };

    const titleMatch = htmlChunk.match(/<title[^>]*>([^<]+)<\/title>/i);
    const standardTitle = titleMatch ? decodeHtmlEntities(titleMatch[1].trim()) : null;

    let title = extractMeta('title') || standardTitle || parsedUrl.hostname;
    let description = extractMeta('description') || `Visit ${parsedUrl.hostname}`;
    let image = extractMeta('image');
    let siteName = extractMeta('site_name') || parsedUrl.hostname;

    // Resolve relative image URLs
    if (image && !image.startsWith('http://') && !image.startsWith('https://')) {
      try {
        image = new URL(image, parsedUrl.origin).href;
      } catch {
        image = null;
      }
    }

    const preview = {
      url: parsedUrl.href,
      domain: siteName || parsedUrl.hostname,
      title: title.length > 100 ? title.substring(0, 97) + '...' : title,
      description: description.length > 200 ? description.substring(0, 197) + '...' : description,
      image
    };

    // Store in cache (limit size to 500 entries)
    if (previewCache.size > 500) {
      const firstKey = previewCache.keys().next().value;
      previewCache.delete(firstKey);
    }
    previewCache.set(cacheKey, preview);

    return res.json({ success: true, ...preview });
  } catch (err) {
    // Fallback card with domain
    const fallback = {
      url: parsedUrl.href,
      domain: parsedUrl.hostname,
      title: parsedUrl.hostname,
      description: `Link to ${parsedUrl.hostname}`,
      image: null
    };
    return res.json({ success: true, ...fallback, warning: err.message });
  }
};

function decodeHtmlEntities(str) {
  if (!str) return '';
  return str
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#039;/g, "'")
    .replace(/&#x27;/g, "'")
    .replace(/&mdash;/g, '—')
    .replace(/&ndash;/g, '–');
}
