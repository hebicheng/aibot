import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// 本地端口/后端地址可在 web/.env.local 覆盖（VITE_PORT / VITE_BACKEND），默认值保持原样
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backend = env.VITE_BACKEND || 'http://localhost:8090';
  return {
    plugins: [react()],
    server: {
      host: '127.0.0.1',
      port: Number(env.VITE_PORT) || 5179,
      strictPort: true,
      proxy: {
        '/api': backend,
        '/files': backend
      }
    }
  };
});
