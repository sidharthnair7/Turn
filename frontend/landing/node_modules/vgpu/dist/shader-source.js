import { malformedShaderSourceError } from "./errors.js";
/** Normalizes public ring-1 shader inputs to raw WGSL. Strings remain first-class; ShaderSource is the loader artifact. */
export function toWgsl(input) {
    if (typeof input === "string")
        return input;
    if (!isObject(input))
        throw malformedShaderSourceError(input);
    if (!("version" in input))
        throw malformedShaderSourceError(input);
    const version = input.version;
    if (version !== 1)
        throw malformedShaderSourceError(input);
    const wgsl = input.wgsl;
    if (typeof wgsl !== "string")
        throw malformedShaderSourceError(input);
    return wgsl;
}
function isObject(value) {
    return typeof value === "object" && value !== null;
}
//# sourceMappingURL=shader-source.js.map