/** @internal Implemented by values that bind themselves to a reflected buffer binding. */
export const BINDING_RESOURCE = Symbol("vgpu.bindingResource");
/** Narrows a user-supplied binding value to the protocol, or `undefined` for anything else. */
export function bindingResourceOf(value) {
    const method = typeof value === "object" && value !== null ? value[BINDING_RESOURCE] : undefined;
    return typeof method === "function" ? value : undefined;
}
/** @internal Resolves named geometry attributes against reflected shader inputs. */
export const geometryLayoutResolver = Symbol("vgpu.geometry.layoutResolver");
//# sourceMappingURL=draw-protocols.js.map