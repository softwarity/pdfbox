import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CodeComponent } from '../code/code.component';

@Component({
  selector: 'app-fonts',
  imports: [CodeComponent, RouterLink],
  template: `
    <h2>Fonts &amp; scripts</h2>

    <p>
      Every glyph is <strong>embedded</strong> (required for PDF/A), so output is reproducible offline.
      A very broad <a href="https://fonts.google.com/noto" target="_blank" rel="noopener">Noto</a> set
      ships with the service, covering Latin/Vietnamese/Cyrillic/Greek, Hebrew, Arabic, Thaana, the
      major Indic scripts, Thai, Lao, Myanmar, Khmer, Georgian, Armenian, Ethiopic, Japanese, Korean,
      Chinese (Simplified &amp; Traditional), plus symbols, math and monochrome emoji.
    </p>

    <div class="callout">
      You normally write nothing. A single document can mix scripts freely and the right faces are
      chosen automatically. The CSS generics <code>serif</code> / <code>sans-serif</code> /
      <code>monospace</code> resolve to <code>Noto Serif</code> / <code>Noto Sans</code> /
      <code>Noto Sans Mono</code>, so a stack ending in a generic always lands on a real embedded face.
    </div>

    <h3>Mixing scripts</h3>
    <p>This just works — no font declarations needed:</p>
    <app-code lang="markup">&lt;p&gt;Tiếng Việt · עברית · العربية · ไทย · हिन्दी · 日本語&lt;/p&gt;</app-code>
    <p>To force a specific family, use CSS as usual:</p>
    <app-code lang="markup">&lt;p style="font-family:'Noto Sans Hebrew'"&gt;שלום עולם&lt;/p&gt;
&lt;p style="font-family:'Noto Sans JP'"&gt;日本語のテキスト&lt;/p&gt;</app-code>

    <h3>Per-document selection (why it's fast)</h3>
    <p>
      openhtmltopdf re-parses every font it is told about on <em>every</em> render, so registering the
      whole set each time would re-parse tens of MB per request. Instead the service scans the source
      for the Unicode blocks it actually contains and registers only the matching faces — a Latin-only
      PDF never pays to parse Thai, Devanagari or CJK.
    </p>

    <h3>CJK and the <code>lang</code> attribute</h3>
    <p>
      Han ideographs are shared across Japanese / Korean / Simplified / Traditional Chinese but render
      with different shapes. The service disambiguates from the document <code>lang</code>
      (<code>ja</code> / <code>ko</code> / <code>zh-Hans</code> / <code>zh-Hant</code>), defaulting to
      Japanese. <strong>Declare <code>lang</code></strong> on elements mixing CJK to get the correct
      Han shapes:
    </p>
    <app-code lang="markup">&lt;p lang="zh-Hans"&gt;简体中文&lt;/p&gt;
&lt;p lang="zh-Hant"&gt;繁體中文&lt;/p&gt;
&lt;p lang="ko"&gt;한국어&lt;/p&gt;</app-code>

    <h3>Where the fonts live</h3>
    <p>
      The light faces are bundled <strong>inside the jar</strong>, so multi-script rendering works
      whether you run the jar, the IDE or the container. The big CJK faces (Korean, Chinese SC/TC) are
      shipped as filesystem fonts in the Docker image under <code>/usr/share/fonts</code> instead, to
      keep the jar small.
    </p>
    <div class="callout warn">
      Running the <strong>bare jar</strong> therefore covers everything <em>except</em> CJK unless you
      add CJK <code>.ttf</code> files yourself. The Docker image covers CJK out of the box.
    </div>

    <h3>Adding your own fonts</h3>
    <p>
      Drop extra <strong><code>.ttf</code></strong> files into <code>/app/fonts</code> (Docker) or any
      directory listed in <code>PDFBOX_FONTS_DIRECTORIES</code> (see
      <a routerLink="/configuration">Configuration</a>); they are indexed at startup. Two caveats:
    </p>
    <ul>
      <li>
        <strong>TrueType only.</strong> The renderer embeds TrueType outlines, so OpenType/CFF
        <code>.otf</code> files are skipped — convert to <code>.ttf</code> first.
      </li>
      <li>
        <strong>Freeze variable fonts.</strong> A variable font embeds at its <em>default</em> master
        (often Thin), so instance it to a static weight first (the image does this for Noto CJK,
        forcing <code>wght=400</code>).
      </li>
    </ul>
    <p>See <a routerLink="/docker">Docker &amp; architectures</a> and <a routerLink="/kubernetes">Kubernetes (Helm)</a> for how to mount extra fonts into a deployment.</p>
  `,
})
export class FontsComponent {}
