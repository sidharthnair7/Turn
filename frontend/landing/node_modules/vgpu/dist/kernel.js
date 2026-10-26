/**
 * Minimal `Gpu` core plus its private kernel.
 *
 * The kernel is the only shared, cross-feature runtime: device handle, error delivery,
 * `settled()` bookkeeping, ownership/teardown ordering and a lazy service registry.
 * It MUST NOT import a feature module (frame, draw, effect, surface, timer, geometry, ...):
 * every service is registered by the feature that owns it, through a token + factory, so a
 * program that never imports the feature never pays for it. See T202-06 negative metafile
 * assertions and the `init-only` bundle budget.
 */
import { Device, validateRequiredFeatures } from "@vgpu/core";
import { unsupportedError, VGPUError } from "./errors.js";
const PHASES = ["scheduler", "resource", "service"];
export function serviceToken(name) {
    return { name };
}
const kernels = new WeakMap();
/** Internal accessor for the kernel of a `Gpu`. Throws for objects this library did not create. */
export function kernelOf(gpu) {
    const kernel = kernels.get(gpu);
    if (!kernel) {
        throw new VGPUError({
            code: "VGPU-GPU-FOREIGN",
            message: "This object was not created by init(); it has no vgpu kernel.",
            fix: "Pass the gpu returned by init() from vgpu, vgpu/node or vgpu/mock.",
            where: "gpu",
        });
    }
    return kernel;
}
class KernelImpl {
    device;
    #services = new Map();
    #owners = new Map(PHASES.map((phase) => [phase, new Set()]));
    #errorListeners = new Set();
    #pendingDeliveries = new Set();
    #settledSources = new Set();
    #disposed = false;
    constructor(device) {
        this.device = device;
    }
    get disposed() { return this.#disposed; }
    service(token, factory) {
        const existing = this.#services.get(token);
        if (existing !== undefined)
            return existing;
        const created = factory(this);
        this.#services.set(token, created);
        return created;
    }
    peekService(token) {
        return this.#services.get(token);
    }
    own(phase, disposer) {
        const set = this.#owners.get(phase);
        set.add(disposer);
        return () => { set.delete(disposer); };
    }
    addErrorListener(cb) {
        this.#errorListeners.add(cb);
        return () => { this.#errorListeners.delete(cb); };
    }
    reportError(error) {
        if (this.#disposed)
            return Promise.resolve();
        const delivery = Promise.resolve().then(() => {
            const listeners = [...this.#errorListeners];
            if (!listeners.length) {
                console.error(error);
                return;
            }
            for (const listener of listeners) {
                try {
                    listener(error);
                }
                catch (listenerError) {
                    console.error(listenerError);
                }
            }
        });
        return this.trackDelivery(delivery);
    }
    trackDelivery(promise) {
        const tracked = Promise.resolve(promise).then(() => undefined, (error) => { console.error(error); });
        this.#pendingDeliveries.add(tracked);
        void tracked.finally(() => this.#pendingDeliveries.delete(tracked));
        return tracked;
    }
    registerSettledSource(source) {
        this.#settledSources.add(source);
        return () => { this.#settledSources.delete(source); };
    }
    async settled() {
        const snapshot = [
            ...this.#pendingDeliveries,
            ...[...this.#settledSources].flatMap((source) => source()),
        ];
        await Promise.allSettled(snapshot);
    }
    dispose() {
        if (this.#disposed)
            return;
        this.#disposed = true;
        for (const phase of PHASES) {
            const set = this.#owners.get(phase);
            // Copy: a disposer usually calls its own release(), mutating the set while we walk it.
            for (const disposer of [...set])
                disposer();
            set.clear();
        }
        this.#services.clear();
        this.#settledSources.clear();
        this.#errorListeners.clear();
        this.device.dispose();
    }
}
/** Builds the minimal `Gpu` for an already-created device and registers its kernel. */
export function attachKernel(device) {
    const kernel = new KernelImpl(device);
    const gpu = {
        device,
        gpu: device.gpu,
        get disposed() { return kernel.disposed; },
        onError: (cb) => kernel.addErrorListener(cb),
        settled: () => kernel.settled(),
        dispose: () => { kernel.dispose(); },
    };
    kernels.set(gpu, kernel);
    return gpu;
}
/** Entry-agnostic core constructor: resolve a device, wrap it in the minimal `Gpu`. */
export async function createCoreGpu(entry, opts = {}, adapterFactory) {
    return attachKernel(await createDevice(entry, opts, adapterFactory));
}
export async function createDevice(entry, opts, adapterFactory) {
    if (opts.adapter || adapterFactory)
        return (opts.adapter ?? adapterFactory()).requestDevice(opts);
    if (entry === "browser")
        return requestBrowserDevice(opts);
    throw unsupportedError("init", `init(${entry}) requires adapterFactory.`);
}
async function requestBrowserDevice(opts) {
    const nav = globalThis.navigator;
    const adapter = await nav.gpu?.requestAdapter({ powerPreference: opts.powerPreference });
    if (!adapter)
        throw unsupportedError("init", "navigator.gpu.requestAdapter() returned null.");
    validateRequiredFeatures(adapter.features, opts.requiredFeatures);
    const gpuDevice = await adapter.requestDevice({ requiredFeatures: opts.requiredFeatures, requiredLimits: opts.requiredLimits });
    return new Device(gpuDevice, adapter.info ?? null);
}
//# sourceMappingURL=kernel.js.map