/*
 * geflip sync Worker — a ~40-line free Cloudflare Worker that stores ONE JSON blob per
 * random sync-id in Workers KV. That blob is the shared state (config + journal + fills)
 * the web app and the RuneLite plugin both read/write, so they sync anywhere over https
 * (phone on mobile data included). No account token in the apps — the unguessable
 * sync-id IS the key. Only someone with your id can touch your blob.
 *
 *   GET  /?id=<syncid>   -> the stored blob (or {})
 *   PUT  /?id=<syncid>   -> replace the blob (body must be JSON, <1 MB)
 *   (POST behaves like PUT)
 */
const CORS = {
	'Access-Control-Allow-Origin': '*',
	'Access-Control-Allow-Methods': 'GET, PUT, POST, OPTIONS',
	'Access-Control-Allow-Headers': 'Content-Type',
};
const json = (obj, status = 200) =>
	new Response(typeof obj === 'string' ? obj : JSON.stringify(obj),
		{ status, headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8' } });

export default {
	async fetch(request, env) {
		if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });

		const id = new URL(request.url).searchParams.get('id');
		// the id is the whole security model, so demand real entropy
		if (!id || !/^[A-Za-z0-9_-]{16,64}$/.test(id)) {
			return json({ error: 'need ?id= of 16-64 url-safe chars' }, 400);
		}
		const key = 'sync:' + id;

		if (request.method === 'GET') {
			const v = await env.GEFLIP.get(key);
			return json(v || '{}');
		}
		if (request.method === 'PUT' || request.method === 'POST') {
			const body = await request.text();
			if (body.length > 1_000_000) return json({ error: 'blob too large (>1MB)' }, 413);
			let posted;
			try { posted = JSON.parse(body); } catch (e) { return json({ error: 'body must be JSON' }, 400); }
			// SHALLOW-MERGE into the existing blob so the two writers don't clobber each
			// other: the web app owns `config`, the plugin owns `fills`/`session`. Each
			// PUTs only its slice; the other's keys survive.
			let existing = {};
			try { existing = JSON.parse((await env.GEFLIP.get(key)) || '{}'); } catch (e) {}
			const merged = JSON.stringify({ ...existing, ...posted });
			if (merged.length > 1_000_000) return json({ error: 'merged blob too large' }, 413);
			await env.GEFLIP.put(key, merged, { expirationTtl: 60 * 60 * 24 * 120 });
			return json({ ok: true, bytes: merged.length });
		}
		return json({ error: 'method not allowed' }, 405);
	},
};
