import { useRef, useState, useEffect } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { useGSAP } from '@gsap/react';
import AeroShards from './components/AeroShards.jsx';

gsap.registerPlugin(ScrollTrigger, useGSAP);

// Theme tokens GSAP tweens between. Keep in sync with :root in styles.css.
const LIGHT = {
  '--bg': '#fbfaf8',
  '--fg': '#15131c',
  '--muted': '#5f5b6b',
  '--line': 'rgba(21, 19, 28, 0.09)',
  '--card': 'rgba(255, 255, 255, 0.66)',
  '--nav': 'rgba(251, 250, 248, 0.72)'
};
const DARK = {
  '--bg': '#0c0b12',
  '--fg': '#f3f1f8',
  '--muted': '#a29db3',
  '--line': 'rgba(243, 241, 248, 0.1)',
  '--card': 'rgba(255, 255, 255, 0.04)',
  '--nav': 'rgba(12, 11, 18, 0.66)'
};

const GAPS = [
  {
    tag: 'Guests',
    title: 'Guests see the floor too',
    body: "A live wait page built from the tables the camera sees, not a host's guess. Join from your phone and watch your place in line."
  },
  {
    tag: 'Setup',
    title: 'No CCTV system needed',
    body: 'One laptop or phone camera runs it. No point-of-sale swap, no install crew, no monthly hardware.'
  },
  {
    tag: 'Staff',
    title: 'Badges, not faces',
    body: 'Staff wear a printed badge, so the camera knows "staff" without knowing who. No face recognition, no per-person speed scores.'
  },
  {
    tag: 'Tables',
    title: 'Ask without waving',
    body: "A table card flipped to HELP or CHECK is read by the camera and routed to that table's server."
  }
];

const STEPS = [
  {
    word: 'See',
    body: 'OpenCV 5 finds people at each table with NanoDet, and reads staff badges and table cards as ArUco markers.'
  },
  {
    word: 'Decide',
    body: 'Each table moves through Seated, Needs bussing and Ready. Not greeted, Check in, Help and Check alerts rise on timers and go red when overdue.'
  },
  {
    word: 'Route',
    body: 'The alert goes to the server who owns that table, or to whoever has the lightest load. The next badge visit clears it on its own.'
  },
  {
    word: 'Tell the guest',
    body: 'The wait page simulates seating the line at the soonest table that fits, and shows an honest estimate.'
  }
];

const PRIVACY = [
  ['Blurred', 'Faces are blurred in every frame Turn shows.'],
  ['Never saved', 'No frame is saved or sent anywhere. Turn keeps counts, not footage.'],
  ['Badge numbers', 'Staff are known by a badge number, never by face.'],
  ['No scores', "Load counts tables, not speed. Turn doesn't score people."]
];

const FACTS = [
  { value: 1, label: 'camera, one you already own' },
  { value: 0, label: 'frames ever saved' },
  { value: 0, label: 'faces recognised' },
  { value: 4, label: 'alert types, routed to the right server' }
];

const MARQUEE = [
  'No face recognition',
  'No frames saved',
  'One camera',
  'Fair workload',
  'Honest wait times',
  'Badges, not faces',
  'Built on OpenCV 5'
];

function useNarrow() {
  const [narrow, setNarrow] = useState(() => window.matchMedia('(max-width: 860px)').matches);
  useEffect(() => {
    const query = window.matchMedia('(max-width: 860px)');
    const update = () => setNarrow(query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  return narrow;
}

export default function App() {
  const root = useRef(null);
  const narrow = useNarrow();
  const [heroGpu, setHeroGpu] = useState(true);
  const [darkGpu, setDarkGpu] = useState(true);

  useGSAP(
    () => {
      const html = document.documentElement;
      gsap.set(html, LIGHT);

      const mm = gsap.matchMedia();

      // Theme: light → dark through "How it works" and "Privacy", then back to light.
      const toTheme = (tokens, dark) => {
        gsap.to(html, { ...tokens, duration: 0.9, ease: 'power2.out', overwrite: 'auto' });
        gsap.to('.dark-shards', { autoAlpha: dark ? 1 : 0, duration: 1.1, ease: 'power2.out', overwrite: 'auto' });
      };

      ScrollTrigger.create({
        trigger: '.dark-zone',
        start: 'top 55%',
        end: 'bottom 45%',
        onEnter: () => toTheme(DARK, true),
        onEnterBack: () => toTheme(DARK, true),
        onLeave: () => toTheme(LIGHT, false),
        onLeaveBack: () => toTheme(LIGHT, false)
      });

      mm.add('(prefers-reduced-motion: no-preference)', () => {
        // Hero intro
        const intro = gsap.timeline({ defaults: { ease: 'expo.out' } });
        intro
          .from('.nav', { y: -24, autoAlpha: 0, duration: 1 })
          .from('.hero .eyebrow', { y: 14, autoAlpha: 0, duration: 0.9 }, '-=0.6')
          .from('.hero h1 .line > span', { yPercent: 110, duration: 1.3, stagger: 0.12 }, '-=0.7')
          .from('.hero .lede', { y: 18, autoAlpha: 0, duration: 1 }, '-=0.9')
          .from('.hero .ctas > *', { y: 14, autoAlpha: 0, duration: 0.8, stagger: 0.08 }, '-=0.8')
          .from('.hero .hint', { autoAlpha: 0, duration: 1 }, '-=0.4')
          .from(
            '.floor-card',
            { y: 40, autoAlpha: 0, rotateX: 8, scale: 0.97, duration: 1.4, transformOrigin: '50% 100%' },
            '-=1.2'
          )
          .from('.floor-card .mini', { y: 12, autoAlpha: 0, duration: 0.7, stagger: 0.07 }, '-=0.9');

        // Hero drifts up and fades a little as you leave it
        gsap.to('.hero-inner', {
          yPercent: -8,
          autoAlpha: 0.35,
          ease: 'none',
          scrollTrigger: { trigger: '.hero', start: 'top top', end: 'bottom top', scrub: true }
        });

        // Gentle float on the floor card
        gsap.to('.floor-card', { y: -8, duration: 3.2, ease: 'sine.inOut', yoyo: true, repeat: -1, delay: 1.6 });

        // Marquee
        const track = root.current.querySelector('.marquee-track');
        gsap.to(track, { xPercent: -50, duration: 38, ease: 'none', repeat: -1 });

        // Section headings
        gsap.utils.toArray('.reveal').forEach(element => {
          gsap.from(element, {
            y: 28,
            autoAlpha: 0,
            duration: 1.1,
            ease: 'power3.out',
            scrollTrigger: { trigger: element, start: 'top 85%' }
          });
        });

        // Cards arrive in a soft stagger
        gsap.set('.gap-card', { autoAlpha: 0 });
        ScrollTrigger.batch('.gap-card', {
          start: 'top 88%',
          onEnter: batch =>
            gsap.fromTo(
              batch,
              { y: 36, autoAlpha: 0 },
              { y: 0, autoAlpha: 1, duration: 1, ease: 'power3.out', stagger: 0.09, overwrite: true }
            )
        });

        // Steps: the rail fills as you scroll, each step lights up as the fill reaches it
        gsap.fromTo(
          '.rail-fill',
          { scaleY: 0 },
          {
            scaleY: 1,
            ease: 'none',
            scrollTrigger: { trigger: '.steps', start: 'top 70%', end: 'bottom 60%', scrub: 0.6 }
          }
        );
        gsap.utils.toArray('.step').forEach(step => {
          gsap.fromTo(
            step,
            { autoAlpha: 0.25, x: 12 },
            {
              autoAlpha: 1,
              x: 0,
              duration: 0.8,
              ease: 'power2.out',
              scrollTrigger: { trigger: step, start: 'top 68%', toggleActions: 'play none none reverse' }
            }
          );
        });

        // Privacy statements
        gsap.set('.privacy-item', { autoAlpha: 0 });
        ScrollTrigger.batch('.privacy-item', {
          start: 'top 88%',
          onEnter: batch =>
            gsap.fromTo(
              batch,
              { y: 24, autoAlpha: 0 },
              { y: 0, autoAlpha: 1, duration: 0.9, ease: 'power3.out', stagger: 0.08, overwrite: true }
            )
        });

        // Counters
        gsap.utils.toArray('.fact-value').forEach(element => {
          const end = Number(element.dataset.value);
          const counter = { value: end === 0 ? 9 : 0 };
          gsap.to(counter, {
            value: end,
            duration: 1.6,
            ease: 'power3.out',
            snap: { value: 1 },
            onUpdate: () => {
              element.textContent = counter.value;
            },
            scrollTrigger: { trigger: element, start: 'top 85%' }
          });
        });

        // Insight lines type in one after another
        gsap.from('.insight-line', {
          autoAlpha: 0,
          x: -10,
          duration: 0.7,
          stagger: 0.18,
          ease: 'power2.out',
          scrollTrigger: { trigger: '.insights-panel', start: 'top 75%' }
        });

        // Final call to action
        gsap.from('.final h2 .line > span', {
          yPercent: 110,
          duration: 1.2,
          stagger: 0.1,
          ease: 'expo.out',
          scrollTrigger: { trigger: '.final', start: 'top 75%' }
        });
      });

      return () => mm.revert();
    },
    { scope: root }
  );

  return (
    <div ref={root} className="page">
      <nav className="nav">
        <a href="/" className="brand">
          Turn<span className="dot" />
        </a>
        <div className="nav-links">
          <a href="#how">How it works</a>
          <a href="#privacy">Privacy</a>
          <a href="/wait">Guest wait page</a>
        </div>
        <a href="/floor" className="btn btn-dark btn-sm">
          Open the floor
        </a>
      </nav>

      <header className="hero">
        <div className="hero-bg" aria-hidden="true">
          <div className="aurora" />
          {heroGpu && (
            <AeroShards
              lightMode
              shardColor="#8fa2ff"
              accentColor="#ff8cc6"
              placement={narrow ? 'full' : 'right'}
              material="glass"
              markers="plus"
              count={narrow ? 700 : 1200}
              depthOfField={0.7}
              vignette={0.25}
              grain={0.04}
              mouseStrength={0.55}
              parallax={0.45}
              onError={error => { console.warn('AeroShards (hero) fell back:', error); setHeroGpu(false); }}
            />
          )}
          <div className="hero-scrim" />
        </div>

        <div className="hero-inner">
          <div className="hero-copy">
            <div className="eyebrow">
              <span className="pulse" /> For small restaurants on a busy night
            </div>
            <h1>
              <span className="line">
                <span>
                  Every table <em className="rainbow-text">covered.</em>
                </span>
              </span>
              <span className="line muted">
                <span>From one camera you already own.</span>
              </span>
            </h1>
            <p className="lede">
              Turn watches the floor so the host doesn't have to. It sees which table has waited too long for a visit,
              sends that task to the right server, keeps everyone's load fair, and shows guests at the door a wait time
              based on what's actually happening.
            </p>
            <div className="ctas">
              <a href="/floor" className="btn btn-rainbow">
                Open the floor
              </a>
              <a href="/wait" className="btn btn-ghost">
                See the guest wait page
              </a>
            </div>
            {heroGpu && <p className="hint">Click the glass to send a pulse. Hold to gather it.</p>}
          </div>

          <div className="floor-card" aria-label="Example of the floor view">
            <div className="floor-card-head">
              <span className="live" /> Floor · Friday, 7:42 pm
            </div>
            <div className="minis">
              <div className="mini overdue">
                <b>Table 2</b>
                <span>Overdue · 0:42</span>
                <small>Not greeted · send Marco</small>
              </div>
              <div className="mini ok">
                <b>Table 1</b>
                <span>Seated · 12:05</span>
                <small>Priya · last visit 2:10 ago</small>
              </div>
              <div className="mini bussing">
                <b>Table 3</b>
                <span>Needs bussing</span>
                <small>Guests left 0:30 ago</small>
              </div>
              <div className="mini wait">
                <b>Wait at the door</b>
                <span className="big">8 min</span>
                <small>for a party of 2</small>
              </div>
            </div>
          </div>
        </div>
      </header>

      <div className="marquee" aria-hidden="true">
        <div className="marquee-track">
          {[...MARQUEE, ...MARQUEE].map((item, index) => (
            <span key={index}>
              {item}
              <i />
            </span>
          ))}
        </div>
      </div>

      <section className="section">
        <div className="section-head reveal">
          <p className="kicker">Why Turn</p>
          <h2>
            What Turn does that <em>the big systems don't.</em>
          </h2>
        </div>
        <div className="gaps">
          {GAPS.map(gap => (
            <article className="gap-card" key={gap.title}>
              <span className="tag">{gap.tag}</span>
              <h3>{gap.title}</h3>
              <p>{gap.body}</p>
            </article>
          ))}
        </div>
      </section>

      <div className="dark-zone">
        <div className="dark-shards" aria-hidden="true">
          {darkGpu && (
            <AeroShards
              shardColor="#7c6cf0"
              accentColor="#ff7ad9"
              placement="left"
              material="glass"
              markers="dots"
              count={narrow ? 450 : 800}
              depthOfField={0.8}
              vignette={0.45}
              glow={0.4}
              speed={0.7}
              mouseStrength={0.45}
              parallax={0.35}
              onError={error => { console.warn('AeroShards (dark) fell back:', error); setDarkGpu(false); }}
            />
          )}
        </div>

        <section className="section how" id="how">
          <div className="how-grid">
            <div className="section-head sticky reveal">
              <p className="kicker">How it works</p>
              <h2>
                See. Decide. Route. <em>Tell the guest.</em>
              </h2>
              <p className="sub">
                Turn only ever sees counts and badge numbers. The decisions happen in a small state machine, not in the
                video.
              </p>
            </div>
            <div className="steps">
              <div className="rail" aria-hidden="true">
                <div className="rail-fill" />
              </div>
              <ol>
              {STEPS.map((step, index) => (
                <li className="step" key={step.word}>
                  <span className="step-num">0{index + 1}</span>
                  <div>
                    <h3>{step.word}</h3>
                    <p>{step.body}</p>
                  </div>
                </li>
              ))}
              </ol>
            </div>
          </div>
        </section>

        <section className="section" id="privacy">
          <div className="section-head reveal">
            <p className="kicker">Privacy by design</p>
            <h2>
              Turn keeps counts, <em>not footage.</em>
            </h2>
          </div>
          <div className="privacy">
            {PRIVACY.map(([title, body]) => (
              <div className="privacy-item" key={title}>
                <h3>{title}</h3>
                <p>{body}</p>
              </div>
            ))}
          </div>
        </section>
      </div>

      <section className="section insights">
        <div className="insights-grid">
          <div className="section-head reveal">
            <p className="kicker">Shift insights</p>
            <h2>
              The night, <em>in plain language.</em>
            </h2>
            <p className="sub">
              Covers served, time to first visit, alerts raised and answered, and how fast tables are reset. Per table
              and per alert type, never per person.
            </p>
          </div>
          <div className="insights-panel reveal">
            <div className="panel-head">
              <span>Insights</span>
              <span className="chip">Demo service</span>
            </div>
            <p className="insight-line">
              <b>Slowest first visit:</b> Table 2 waited 34 s
            </p>
            <p className="insight-line">
              <b>Response time</b> is tracked for each alert type
            </p>
            <p className="insight-line">
              <b>Table resets</b> are timed from guests leaving to Ready
            </p>
            <p className="insight-line">
              <b>Load</b> counts tables per server, not speed
            </p>
          </div>
        </div>

        <div className="facts">
          {FACTS.map(fact => (
            <div className="fact reveal" key={fact.label}>
              <span className="fact-value" data-value={fact.value}>
                {fact.value}
              </span>
              <span className="fact-label">{fact.label}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="final">
        <div className="final-glow" aria-hidden="true" />
        <h2>
          <span className="line">
            <span>Run tonight's service</span>
          </span>
          <span className="line">
            <span>
              from <em className="rainbow-text">one camera.</em>
            </span>
          </span>
        </h2>
        <div className="ctas center">
          <a href="/floor" className="btn btn-rainbow">
            Open the floor
          </a>
          <a href="/wait" className="btn btn-ghost">
            Guest wait page
          </a>
        </div>
      </section>

      <footer className="foot">
        <div>
          <b>Turn</b>. Built with Java, Spring Boot and OpenCV 5 for Devpost's Build With AI: Basics.
        </div>
        <a href="https://github.com/sidharthnair7/Turn">Source on GitHub</a>
      </footer>
    </div>
  );
}
