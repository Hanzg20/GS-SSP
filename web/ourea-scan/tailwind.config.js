/** @type {import('tailwindcss').Config} */
// GoldSky brand: the gold and the sky of the logo (core/ui ic_goldsky_logo.xml).
// Gold = what the customer acts on and money (main buttons, prices, totals);
// sky = information and status (order progress, Beaver, hints). Gold buttons
// carry ink text: white on #FFB800 doesn't reach 4.5:1.
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        gold: { 50: '#FFF8E6', 100: '#FFEFC2', 200: '#FFE08A', 300: '#FFD04D', 400: '#FFC21F', 500: '#FFB800', 600: '#FF9F00', 700: '#B26A00', 800: '#7A4800' },
        sky: { 50: '#E8F8FE', 100: '#C9EEFC', 200: '#93DDF8', 400: '#22BDF2', 500: '#00AEEF', 600: '#0091C7', 700: '#00719C', 800: '#00577A' },
        ink: { DEFAULT: '#0F172A', soft: '#334155', mute: '#64748B' },
        paper: '#F7F8FA',
      },
      fontFamily: {
        sans: ['Inter', '"Noto Sans SC"', '"Noto Sans Arabic"', 'system-ui', '-apple-system', 'sans-serif'],
      },
      boxShadow: {
        card: '0 1px 2px rgba(15,23,42,.04), 0 4px 16px rgba(15,23,42,.06)',
        lift: '0 8px 30px rgba(15,23,42,.18)',
      },
    },
  },
  plugins: [],
}
