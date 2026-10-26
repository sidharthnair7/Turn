import { bindGroupLayoutMetadata } from "@vgpu/core";
import { isTarget } from "./target-utils.js";
import { compileDisposedError, compileFailedError, compileSignatureInvalidError, constantsInvalidError, entryInvalidError, pipelineLayoutGapError } from "./errors.js";
let nextShaderModuleId = 1;
let nextPipelineLayoutId = 1;
const shaderModuleIds = new WeakMap();
const pipelineLayoutIds = new WeakMap();
export function normalizeSignature(arg) {
    if (isTarget(arg)) {
        return {
            colors: arg.colors.map((color) => color.format),
            depth: arg.depth?.format,
            sampleCount: arg.sampleCount,
        };
    }
    if (typeof arg !== "object" || arg === null)
        return { colors: [] };
    return {
        colors: Array.isArray(arg.colors) ? [...arg.colors] : (arg.colors ?? []),
        depth: arg.depth,
        sampleCount: arg.sampleCount ?? 1,
    };
}
export function signatureKeyOf(sig) {
    return `${sig.colors.join(",")}:${sig.depth ?? "none"}:${sig.sampleCount ?? 1}`;
}
export function validateTargetSignature(sig, where) {
    if (!Array.isArray(sig.colors) || sig.colors.length === 0)
        throw compileSignatureInvalidError(where, "colors must be a non-empty array.");
    const invalidColor = sig.colors.find((format) => typeof format !== "string" || format.length === 0);
    if (invalidColor !== undefined)
        throw compileSignatureInvalidError(where, `colors must contain only GPUTextureFormat strings; received ${String(invalidColor)}.`);
    if (sig.depth !== undefined && (typeof sig.depth !== "string" || sig.depth.length === 0))
        throw compileSignatureInvalidError(where, "depth must be a GPUTextureFormat string.");
    const sampleCount = sig.sampleCount ?? 1;
    if (sampleCount !== 1 && sampleCount !== 4)
        throw compileSignatureInvalidError(where, `sampleCount must be 1 or 4; received ${String(sampleCount)}.`);
}
export function pipelineKeyOf(parts) {
    const base = `${idFor(shaderModuleIds, parts.module, () => nextShaderModuleId++)}|${idFor(pipelineLayoutIds, parts.pipelineLayout, () => nextPipelineLayoutId++)}|${vertexLayoutHash(parts.vertexBufferLayouts ?? [])}|${signatureKeyOf(parts.signature)}`;
    const primitive = parts.topology || parts.stripIndexFormat ? `${base}|${parts.topology ?? "triangle-list"}|${parts.stripIndexFormat ?? "none"}` : base;
    const culled = parts.cullMode || parts.frontFace ? `${primitive}|${parts.cullMode ?? "none"}|${parts.frontFace ?? "ccw"}` : primitive;
    const clipped = parts.unclippedDepth ? `${culled}|unclipped` : culled;
    const withDepth = parts.depthKey ? `${clipped}|${parts.depthKey}` : clipped;
    const withStencil = parts.stencilKey ? `${withDepth}|${parts.stencilKey}` : withDepth;
    const withMultisample = parts.multisampleKey ? `${withStencil}|${parts.multisampleKey}` : withStencil;
    const withConstants = parts.constantsKey ? `${withMultisample}|${parts.constantsKey}` : withMultisample;
    // The shader module is shared per byte-identical source, so entry point names must key variants themselves.
    const withEntry = parts.entryKey ? `${withConstants}|${parts.entryKey}` : withConstants;
    return parts.fragmentKey ? `${withEntry}|${parts.fragmentKey}` : withEntry;
}
/**
 * Selects the entry point a pipeline stage compiles: the first entry point of the stage when no name is given
 * (exactly today's behavior), or the named one — validated to exist and to have the requested stage. Callers must
 * run this selection before deriving anything from the result (binding visibility, storage-stage limits, bind
 * group layouts, vertex input layouts), so the whole pipeline reflects the chosen variant.
 */
export function selectEntryPoint(label, entryPoints, stage, name, where) {
    if (name === undefined)
        return entryPoints.find((entry) => entry.stage === stage);
    if (typeof name !== "string") {
        throw entryInvalidError(label, `${stage} received ${previewConstant(name)}; expected an entry point name string.`, where);
    }
    // WGSL forbids duplicate function names, so a name identifies at most one entry point across all stages.
    const named = entryPoints.find((entry) => entry.name === name);
    if (!named) {
        throw entryInvalidError(label, `"${name}" matches no entry point in the shader; available entry points: ${availableEntryPoints(entryPoints)}.`, where);
    }
    if (named.stage !== stage) {
        throw entryInvalidError(label, `"${name}" is a @${named.stage} entry point, not @${stage}; available entry points: ${availableEntryPoints(entryPoints)}.`, where);
    }
    return named;
}
function availableEntryPoints(entryPoints) {
    if (!entryPoints.length)
        return "none";
    return entryPoints.map((entry) => `"${entry.name}" (@${entry.stage})`).join(", ");
}
/**
 * Validates a `constants` option against the shader's reflected `override` declarations and normalizes it into
 * the GPUProgrammableStage constants record (booleans become 1/0 — GPUPipelineConstantValue "is a `double`", and
 * WebGPU converts it "to WGSL type of the pipeline-overridable constant (bool/i32/u32/f32/f16)") plus a
 * deterministic pipeline-cache key fragment.
 *
 * WebGPU keys each override by its pipeline-overridable constant identifier string — "the pipeline constant ID of
 * the constant if its declaration specifies one, and otherwise the constant's identifier name" — and key matching
 * is module-level: "The pipeline-overridable constant is not required to be statically used by entryPoint." One
 * record is therefore valid for every stage of the module and needs no per-stage filtering. The spec's
 * must-provide rule is per constant "statically used by entryPoint"; reflection does not track override
 * reachability per entry point, so the no-default check here is conservatively module-level — providing a value
 * for an unused override is always legal, and requiring one for an unused no-default override never produces an
 * invalid pipeline.
 */
export function normalizeConstantsOptions(label, value, overrides, where) {
    if (value !== undefined && (typeof value !== "object" || value === null || Array.isArray(value))) {
        throw constantsInvalidError(label, `received ${previewConstant(value)}; expected { overrideNameOrId: number | boolean }.`, where);
    }
    const byIdentifier = new Map(overrides.map((override) => [overrideIdentifierOf(override), override]));
    const constants = {};
    for (const [key, entry] of Object.entries(value ?? {})) {
        if (!byIdentifier.has(key)) {
            throw constantsInvalidError(label, `"${key}" matches no override in the shader; available overrides: ${availableOverrides(overrides)}.`, where);
        }
        if (typeof entry === "boolean") {
            constants[key] = entry ? 1 : 0;
            continue;
        }
        if (typeof entry !== "number" || !Number.isFinite(entry)) {
            throw constantsInvalidError(label, `"${key}" received ${previewConstant(entry)}; use a finite number or a boolean (WebGPU converts the value to the override's WGSL type, and NaN/Infinity fail that conversion).`, where);
        }
        constants[key] = entry;
    }
    // WebGPU: "If the pipeline-overridable constant identified by key does not have a default value,
    // descriptor.constants must contain key." — checked module-level here; see the function comment.
    for (const override of overrides) {
        const identifier = overrideIdentifierOf(override);
        if (override.defaultValue === undefined && !(identifier in constants)) {
            throw constantsInvalidError(label, `override '${override.name}' has no default value and must be provided; add constants: { "${identifier}": value }.`, where);
        }
    }
    // No overridden values behaves exactly like an absent option; descriptors and pipeline cache keys stay byte-identical.
    if (Object.keys(constants).length === 0)
        return {};
    return { constants, constantsKey: constantsKeyFor(constants) };
}
function overrideIdentifierOf(override) {
    return override.id !== undefined ? String(override.id) : override.name;
}
function availableOverrides(overrides) {
    if (!overrides.length)
        return "none";
    return overrides.map((override) => override.id !== undefined ? `"${override.id}" (@id of ${override.name})` : `"${override.name}"`).join(", ");
}
function constantsKeyFor(constants) {
    return `cn~${Object.entries(constants).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([key, entry]) => `${key}=${entry}`).join("~")}`;
}
function previewConstant(value) {
    if (typeof value === "string")
        return `"${value}"`;
    try {
        return JSON.stringify(value) ?? String(value);
    }
    catch {
        return String(value);
    }
}
export function createShaderModuleCache(device) {
    const modules = new Map();
    return {
        get(source, label) {
            let module = modules.get(source);
            if (!module) {
                // GPUShaderModule is immutable; the first creator's label wins for byte-identical WGSL.
                module = device.gpu.createShaderModule({ label, code: source });
                modules.set(source, module);
            }
            return module;
        },
        dispose() { modules.clear(); },
    };
}
export function createPipelineLayoutCache(device) {
    const layouts = new Map();
    return {
        get(bindGroupLayouts) {
            const key = pipelineLayoutKeyOf(bindGroupLayouts);
            let layout = layouts.get(key);
            if (!layout) {
                layout = device.gpu.createPipelineLayout({ bindGroupLayouts: contiguousLayouts(bindGroupLayouts) });
                layouts.set(key, layout);
            }
            return layout;
        },
        dispose() { layouts.clear(); },
    };
}
export function createPipelineStore(device, opts = {}) {
    return new DevicePipelineStore(device, opts);
}
class DevicePipelineStore {
    device;
    #entries = new Map();
    #tracked = new Set();
    #errorSink;
    #unregisterSettledSource;
    #disposed = false;
    constructor(device, opts) {
        this.device = device;
        this.#errorSink = opts.errorSink ?? (() => undefined);
        this.#unregisterSettledSource = opts.registerSettledSource?.(() => [...this.#tracked]);
    }
    getReady(key) {
        return this.#entries.get(key)?.pipeline;
    }
    getSync(key, create, ctx) {
        this.#assertUsable(ctx.where);
        const existing = this.#entries.get(key);
        if (existing?.pipeline)
            return existing.pipeline;
        const entry = existing ?? {};
        if (!existing)
            this.#entries.set(key, entry);
        const pipeline = this.#createSyncPipeline(key, entry, create, ctx);
        if (!pipeline) {
            if (!entry.pending)
                this.#entries.delete(key);
            return undefined;
        }
        entry.pipeline = pipeline;
        entry.pending?.resolve(pipeline);
        entry.pending = undefined;
        return pipeline;
    }
    getAsync(key, create, ctx) {
        this.#assertUsable(ctx.where);
        const existing = this.#entries.get(key);
        if (existing?.pipeline)
            return Promise.resolve(existing.pipeline);
        if (existing?.pending)
            return existing.pending.promise;
        const entry = {};
        const pending = createDeferred();
        entry.pending = pending;
        this.#entries.set(key, entry);
        let native;
        try {
            native = create();
        }
        catch (cause) {
            const error = compileFailedError(ctx.where, cause, ctx.signature);
            pending.reject(error);
            this.#entries.delete(key);
            return pending.promise;
        }
        this.#track(native);
        native.then((pipeline) => {
            if (this.#entries.get(key) !== entry || entry.pipeline || entry.pending !== pending)
                return;
            entry.pipeline = pipeline;
            entry.pending = undefined;
            pending.resolve(pipeline);
        }, (cause) => {
            if (this.#entries.get(key) !== entry || entry.pipeline || entry.pending !== pending)
                return;
            entry.pending = undefined;
            this.#entries.delete(key);
            pending.reject(compileFailedError(ctx.where, cause, ctx.signature));
        });
        return pending.promise;
    }
    dispose() {
        if (this.#disposed)
            return;
        this.#disposed = true;
        const error = compileDisposedError("gpu.dispose");
        for (const entry of this.#entries.values())
            entry.pending?.reject(error);
        this.#entries.clear();
        this.#tracked.clear();
        this.#unregisterSettledSource?.();
    }
    #createSyncPipeline(key, entry, create, ctx) {
        const gpu = this.device.gpu;
        const scoped = typeof gpu.pushErrorScope === "function" && typeof gpu.popErrorScope === "function";
        if (scoped)
            gpu.pushErrorScope("validation");
        try {
            const pipeline = create();
            if (scoped)
                this.#trackSyncErrorScope(key, entry, ctx);
            return pipeline;
        }
        catch (cause) {
            if (scoped)
                this.#suppressSyncErrorScopePop();
            const error = compileFailedError(ctx.where, cause, ctx.signature);
            void this.#errorSink(error);
            return undefined;
        }
    }
    #trackSyncErrorScope(key, entry, ctx) {
        const pop = this.device.gpu.popErrorScope()
            .then((nativeError) => {
            if (!nativeError)
                return;
            const error = compileFailedError(ctx.where, nativeError, ctx.signature);
            if (this.#entries.get(key) === entry)
                this.#entries.delete(key);
            return this.#errorSink(error);
        }, (cause) => {
            const error = compileFailedError(ctx.where, cause, ctx.signature);
            if (this.#entries.get(key) === entry)
                this.#entries.delete(key);
            return this.#errorSink(error);
        });
        this.#track(pop);
    }
    #suppressSyncErrorScopePop() {
        const pop = this.device.gpu.popErrorScope?.();
        if (pop)
            void pop.catch(() => undefined);
    }
    #assertUsable(where) {
        if (!this.#disposed)
            return;
        throw compileDisposedError(where);
    }
    #track(promise) {
        this.#tracked.add(promise);
        void promise.catch(() => undefined).then(() => this.#tracked.delete(promise), () => this.#tracked.delete(promise));
    }
}
function createDeferred() {
    let resolve;
    let reject;
    const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
    void promise.catch(() => undefined);
    return { promise, resolve, reject };
}
function idFor(ids, value, next) {
    let id = ids.get(value);
    if (!id) {
        id = next();
        ids.set(value, id);
    }
    return id;
}
function vertexLayoutHash(layouts) {
    return JSON.stringify(layouts.map((layout) => ({
        arrayStride: layout.arrayStride,
        stepMode: layout.stepMode ?? "vertex",
        attributes: [...layout.attributes].map((attribute) => ({
            shaderLocation: attribute.shaderLocation,
            offset: attribute.offset,
            format: attribute.format,
        })),
    })));
}
function pipelineLayoutKeyOf(layouts) {
    return JSON.stringify([...layouts.entries()].map(([group, layout]) => ({ group, entries: layoutEntries(layout) })));
}
function contiguousLayouts(bindGroupLayouts) {
    const maxGroup = Math.max(-1, ...bindGroupLayouts.keys());
    const layouts = [];
    for (let i = 0; i <= maxGroup; i++)
        layouts.push(requiredLayout(bindGroupLayouts, i));
    return layouts;
}
function requiredLayout(bindGroupLayouts, group) {
    const layout = bindGroupLayouts.get(group);
    if (!layout)
        throw pipelineLayoutGapError(group);
    return layout;
}
function layoutEntries(layout) {
    return (bindGroupLayoutMetadata(layout)?.entries ?? []).map((entry) => ({
        binding: entry.binding,
        visibility: entry.visibility,
        buffer: entry.buffer ? { ...entry.buffer } : undefined,
        sampler: entry.sampler ? { ...entry.sampler } : undefined,
        texture: entry.texture ? { ...entry.texture } : undefined,
        storageTexture: entry.storageTexture ? { ...entry.storageTexture } : undefined,
        externalTexture: entry.externalTexture ? { ...entry.externalTexture } : undefined,
    }));
}
//# sourceMappingURL=pipeline-store.js.map