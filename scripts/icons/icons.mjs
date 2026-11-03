// Renders every icon the app ships from the one master, art/icon.svg
// (owner, 2026-11-02): never draw or resize a PNG by hand.
//
//   cd scripts/icons && npm ci && node icons.mjs [preview-dir]
//
// Writes, per density (mdpi 1x … xxxhdpi 4x):
//   mipmap-*/ic_launcher.png             48 dp, the house on its ground, rounded (before API 26)
//   mipmap-*/ic_launcher_round.png       48 dp, the same in a circle
//   mipmap-*/ic_launcher_foreground.png  108 dp, the adaptive icon's house (the ground is a colour)
//   mipmap-*/ic_launcher_monochrome.png  108 dp, the themed icon (API 33): the house in white
//   drawable-*/ic_stat_haus.png          24 dp, the notification icon: white, the lines bolder
// and store_icon.png (512 px, the store listing). With a preview dir it also
// writes the launcher as a launcher masks it at 192 px, at 24 px, and the
// 24 px one blown up pixel for pixel.
import { Resvg } from '@resvg/resvg-js'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const res = path.join(root, 'app/src/main/res')
const master = fs.readFileSync(path.join(root, 'art/icon.svg'), 'utf8')

const DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 }

/** The master without its ground: the house alone, on transparent. */
const motif = master.replace(/<rect id="ground"[^>]*\/>/, '')
/** The house in white, strokes and fills alike: Android tints it (themed icon, notification). */
const white = motif.replaceAll('#3ddc84', '#ffffff')
/**
 * The notification icon is drawn at 24 dp, where the master's 6-unit line is
 * under 1.5 px: there the house goes to 8 units, so the mark stays legible.
 * The prompt and cursor shrink to 0.72 around their own centre (their lines
 * drawn at about 7 units) so a clear gap stays between them and the walls:
 * at full size they ran into the house and blurred (owner, 2026-11-03).
 */
const PROMPT_SCALE = 0.72
const bold = white
  .replace('stroke-width="6"', 'stroke-width="8"')
  .replace(
    /(<path d="M39 51[^>]*\/>\s*<rect id="cursor"[^>]*\/>)/,
    `<g transform="translate(54.5 60) scale(${PROMPT_SCALE}) translate(-54.5 -60)">$1</g>`,
  )
  .replace('stroke-width="6.5"', 'stroke-width="10"')
  .replace('y="64.5" width="15" height="6"', 'y="62.25" width="15" height="9.5"')

/** [svg] seen through [box] (x y w h), clipped to [clip] (an SVG shape in the box's units) when given. */
function view(svg, box, clip) {
  let out = svg.replace('viewBox="0 0 108 108"', `viewBox="${box}"`)
  if (clip) {
    out = out
      .replace(/(<svg[^>]*>)/, `$1<clipPath id="clip">${clip}</clipPath><g clip-path="url(#clip)">`)
      .replace('</svg>', '</g></svg>')
  }
  return out
}

/** The visible 72 dp of the 108 dp canvas: what a launcher shows of an adaptive icon. */
const VISIBLE = '18 18 72 72'
const ROUNDED = '<rect x="18" y="18" width="72" height="72" rx="16"/>'
const CIRCLE = '<circle cx="54" cy="54" r="36"/>'
/** The house's own bounds with a little air: the notification icon has no ground to spare. */
const HOUSE = '25 25 58 58'

function png(svg, px) {
  return new Resvg(svg, { fitTo: { mode: 'width', value: Math.round(px) } }).render().asPng()
}

function write(file, bytes) {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, bytes)
  console.log(path.relative(root, file))
}

for (const [name, scale] of Object.entries(DENSITIES)) {
  write(path.join(res, `mipmap-${name}/ic_launcher.png`), png(view(master, VISIBLE, ROUNDED), 48 * scale))
  write(path.join(res, `mipmap-${name}/ic_launcher_round.png`), png(view(master, VISIBLE, CIRCLE), 48 * scale))
  write(path.join(res, `mipmap-${name}/ic_launcher_foreground.png`), png(motif, 108 * scale))
  write(path.join(res, `mipmap-${name}/ic_launcher_monochrome.png`), png(white, 108 * scale))
  write(path.join(res, `drawable-${name}/ic_stat_haus.png`), png(view(bold, HOUSE), 24 * scale))
}
write(path.join(root, 'store_icon.png'), png(view(master, VISIBLE), 512))

const preview = process.argv[2]
if (preview) {
  const launcher = view(master, VISIBLE, ROUNDED)
  write(path.join(preview, 'launcher-192.png'), png(launcher, 192))
  const small = png(launcher, 24)
  write(path.join(preview, 'launcher-24.png'), small)
  const zoomed = `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="240" height="240">` +
    `<image width="240" height="240" image-rendering="optimizeSpeed" xlink:href="data:image/png;base64,${small.toString('base64')}"/></svg>`
  write(path.join(preview, 'launcher-24-zoomed.png'), png(zoomed, 240))
  const stat = png(view(bold, HOUSE), 24)
  write(path.join(preview, 'notification-24.png'), stat)
  const statZoomed = `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="240" height="240">` +
    `<rect width="240" height="240" fill="#0c120e"/><image width="240" height="240" image-rendering="optimizeSpeed" xlink:href="data:image/png;base64,${stat.toString('base64')}"/></svg>`
  write(path.join(preview, 'notification-24-zoomed.png'), png(statZoomed, 240))
}
