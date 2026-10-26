import { reflectSource } from "@vgpu/wgsl/reflect-source";
import { InternalDraw, encodeDraw } from "./draw.js";
import { isTarget } from "./target-utils.js";
import { FRAME_DRAWABLE } from "./frame-protocols.js";
import { liveKernel } from "./live-kernel.js";
import { renderService } from "./render-service.js";
import { toWgsl } from "./shader-source.js";
import { unsupportedError } from "./errors.js";
/**
 * Fullscreen shader pass of this gpu: the fragment shader is enough, the fullscreen triangle
 * vertex stage is generated when the source has no vertex entry point.
 *
 * An effect is a draw with a fixed vertex stage, so it shares the gpu's single render service with
 * `draw()`: same pipeline store, same bind group cache, same shader module and layout caches.
 */
export function effect(gpu, source, opts = {}) {
    // Vertex buffers belong to the generated fullscreen stage, so geometry here would silently do
    // nothing. Reject the option instead of ignoring it.
    if ("geometry" in opts)
        throw unsupportedError("effect", "effect() never accepts vertex buffers; use draw(gpu, { shader, geometry: geometry(gpu, descriptor) }).");
    const kernel = liveKernel(gpu, "effect");
    const render = renderService(kernel);
    return new InternalEffect(kernel.device, toWgsl(source), opts, render.binds, undefined, render.pipelines, render.shaderModules, render.pipelineLayouts, (error) => kernel.reportError(error), (promise) => { void kernel.trackDelivery(promise); });
}
const effectImpls = new WeakMap();
export class InternalEffect {
    get gpu() { return effectImpl(this).gpu; }
    constructor(device, source, opts = {}, cache, defaultTarget, pipelineStore, shaderModules, pipelineLayouts, errorSink, trackSettled) {
        const shader = fullscreenSource(source);
        const impl = new InternalDraw(device, shader, { shader, set: opts.set, label: opts.label ?? "effect", blend: opts.blend, writeMask: opts.writeMask }, cache, defaultTarget, pipelineStore, shaderModules, pipelineLayouts, errorSink, trackSettled);
        effectImpls.set(this, impl);
    }
    set(values) { effectImpl(this).set(values); return this; }
    draw(target = {}) { effectImpl(this).draw(isTarget(target) ? { target } : target); }
    compile(target) { return effectImpl(this).compile(target).then(() => this); }
    compileSync(target) { effectImpl(this).compileSync(target); return this; }
    /** @internal FramePass delegates here; not part of the frozen public Effect surface. */
    encode(pass, target, opts = {}, claimValidation) {
        encodeDraw(effectImpl(this), pass, target, opts, claimValidation);
    }
    /**
     * Frame drawable protocol: an effect is encoded as its underlying draw, so it reuses that draw's
     * protocol object — same encode path, same depth/stencil metadata for read-only passes.
     */
    get [FRAME_DRAWABLE]() { return effectImpl(this)[FRAME_DRAWABLE]; }
}
export function effectDraw(effect) { return effectImpl(effect); }
function effectImpl(effect) {
    const impl = effectImpls.get(effect);
    if (!impl)
        throw new TypeError("Invalid Effect instance");
    return impl;
}
export function fullscreenSource(source) {
    if (hasVertexEntry(source))
        return source;
    return `
struct VgpuFullscreenVertexOut {
  @builtin(position) position: vec4f,
  @location(0) uv: vec2f,
};
@vertex fn vgpu_fullscreen_vs(@builtin(vertex_index) vi: u32) -> VgpuFullscreenVertexOut {
  var pos = array<vec2f, 3>(vec2f(-1.0, -1.0), vec2f(3.0, -1.0), vec2f(-1.0, 3.0));
  var uv = array<vec2f, 3>(vec2f(0.0, 1.0), vec2f(2.0, 1.0), vec2f(0.0, -1.0));
  var out: VgpuFullscreenVertexOut;
  out.position = vec4f(pos[vi], 0.0, 1.0);
  out.uv = uv[vi];
  return out;
}
${source}`;
}
function hasVertexEntry(source) {
    return reflectSource(source, "effect.wgsl").entryPoints.some((entry) => entry.stage === "vertex");
}
//# sourceMappingURL=effect.js.map