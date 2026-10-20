/// <reference types="vitest/config" />
import { createReadStream, statSync } from 'node:fs'
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig, type Plugin } from 'vite'

// Dev server only: forward /api to the Spring backend.
// TODO: backend port not decided; 8080 is the Spring Boot default. Override with API_PROXY_TARGET.
const apiTarget = process.env.API_PROXY_TARGET ?? 'http://localhost:8080'

// Dev server only: the recommended lists' covers are files the web server serves at /covers/ (production: nginx, from
// /srv/chaekcheck/covers; user 2026-10-20). Here they come from the lists folder; override with COVERS_DIR.
const coversDir = process.env.COVERS_DIR ?? resolve(__dirname, '../scripts/crawl/lists/covers')
const COVER_NAME = /^\/covers\/([0-9a-f]{40}\.jpg)$/

function covers(): Plugin {
  return {
    name: 'chaekcheck-covers',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const name = COVER_NAME.exec(req.url?.split('?')[0] ?? '')?.[1]
        if (!name) return next()
        const file = resolve(coversDir, name)
        try {
          statSync(file)
        } catch {
          res.statusCode = 404
          res.end()
          return
        }
        res.setHeader('Content-Type', 'image/jpeg')
        createReadStream(file).pipe(res)
      })
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), covers()],
  server: {
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    css: { modules: { classNameStrategy: 'non-scoped' } },
  },
})
