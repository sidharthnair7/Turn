export function createBindGroupCache() {
    const entries = new Map();
    return {
        getOrCreate(drawId, group, identityTuple, factory) {
            const identities = identityTuple.map(identityKey);
            const key = `${drawId}:${group}:${identities.join("|")}`;
            const existing = entries.get(key);
            if (existing)
                return existing.bindGroup;
            const bindGroup = factory();
            entries.set(key, { identities, bindGroup });
            return bindGroup;
        },
        evictIdentity(identity) {
            const needle = identityKey(identity);
            for (const [key, entry] of entries) {
                if (entry.identities.includes(needle))
                    entries.delete(key);
            }
        },
        clearDraw(drawId) {
            const prefix = `${drawId}:`;
            for (const key of entries.keys())
                if (key.startsWith(prefix))
                    entries.delete(key);
        },
        dispose() {
            entries.clear();
        },
    };
}
export function identityKey(identity) {
    if (typeof identity === "string" || typeof identity === "number")
        return String(identity);
    return `${identity.kind}:${identity.id}`;
}
export function subscribeEviction(resource, cache) {
    const identity = resource.resourceIdentity;
    if (!identity || typeof resource.onDestroy !== "function")
        return undefined;
    return resource.onDestroy(() => cache.evictIdentity(identity));
}
//# sourceMappingURL=bind-cache.js.map