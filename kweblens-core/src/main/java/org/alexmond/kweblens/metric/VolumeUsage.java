package org.alexmond.kweblens.metric;

/**
 * How full one PersistentVolumeClaim's volume is, as kubelet reports it.
 *
 * <p>
 * <b>{@code capacityBytes} is not necessarily the claim's size.</b> kubelet reports the
 * filesystem backing the volume, and for provisioners that do not enforce a per-volume
 * quota — hostPath, local-path, and plain NFS mounts — that is the node's disk or the
 * whole share. Measured on a real cluster: ten NFS claims requesting between 1Gi and 30Gi
 * all reported the same 3.2 TB, and four local-path claims requesting 64Mi to 10Gi all
 * reported the same 41.6 GB.
 *
 * <p>
 * So a caller must decide whether the number describes the claim before showing it as the
 * claim's — see {@link #plausibleFor}. Presenting it blindly would report a 64Mi claim as
 * "41.6 GB, 38% used", which is not a rounding error but a different volume entirely.
 */
public record VolumeUsage(String namespace, String claim, long usedBytes, long capacityBytes) {

	/**
	 * Above this ratio, the reported capacity is treated as the backing filesystem rather
	 * than the claim. Deliberately generous: filesystem overhead and rounding mean a
	 * claim legitimately reports a bit less or more than requested, while the mismatch
	 * this catches is orders of magnitude — 3.2 TB against a 1Gi request is a factor of
	 * 3000.
	 */
	private static final double MAX_OVERSHOOT = 4.0;

	/** Fraction of the volume in use, or -1 when the capacity is unknown. */
	public double usedFraction() {
		return (this.capacityBytes > 0) ? (double) this.usedBytes / this.capacityBytes : -1;
	}

	/**
	 * Whether this reading actually describes the claim of the given requested size.
	 *
	 * <p>
	 * A requested size of zero (or unknown) means we cannot tell, and the honest answer
	 * is no: without something to compare against there is no way to distinguish a real
	 * per-volume quota from a whole node's disk.
	 */
	public boolean plausibleFor(long requestedBytes) {
		if (requestedBytes <= 0 || this.capacityBytes <= 0) {
			return false;
		}
		return (double) this.capacityBytes / requestedBytes <= MAX_OVERSHOOT;
	}

}
