package org.alexmond.kweblens.web.files;

import java.util.ArrayList;
import java.util.List;

/**
 * The POSIX-shell snippets the file browser runs inside a container, and the rules they
 * follow.
 *
 * <p>
 * <strong>Paths are never interpolated.</strong> Each script is invoked as
 * {@code sh -c "<script>" kweblens-files <path> [<extra>]} through the Kubernetes exec
 * API, which takes an argv array — no shell parses the outer command line, and the script
 * only ever sees the path as {@code "$1"}. A filename containing spaces, newlines,
 * quotes, {@code ;} or {@code $(rm -rf /)} is therefore inert. Every path reaching here
 * has already been through {@link PodFilePath#normalize(String)}, so it is absolute; that
 * invariant is why the scripts can omit {@code --} guards that busybox does not always
 * support.
 *
 * <p>
 * <strong>Why not {@code ls -la}.</strong> The obvious implementation parses
 * {@code ls -la} output, which the reference extension does. It is ambiguous for
 * filenames containing spaces or newlines and its columns differ between busybox, toybox
 * and GNU coreutils. Instead the listing script iterates the shell's own glob expansion
 * and emits NUL-delimited fields with {@code printf}, which is unambiguous for every
 * legal POSIX filename. Metadata comes from {@code stat -c} (supported by coreutils,
 * busybox and toybox alike); when {@code stat} is missing the entry still lists, with
 * empty metadata, rather than the whole directory failing.
 *
 * <p>
 * <strong>Portability floor.</strong> {@code sh}, {@code printf}, {@code rm},
 * {@code cat}, {@code wc}. A container without a shell (distroless, scratch) cannot be
 * browsed at all, and is reported as such — see {@link PodFileService}.
 */
final class PodFileScripts {

	/** Argument zero — shows up in the container's process list, so name it usefully. */
	static final String ARGV0 = "kweblens-files";

	/**
	 * {@code $1} = directory, {@code $2} = max entries. Emits the resolved directory,
	 * then five NUL-terminated fields per entry (name, type, {@code stat} blob, symlink
	 * target, symlink target type), then optionally a truncation marker.
	 */
	static final String LIST = """
			if [ ! -e "$1" ]; then printf 'missing\\n' >&2; exit 3; fi
			if [ ! -d "$1" ]; then printf 'not-a-directory\\n' >&2; exit 4; fi
			cd -P "$1" 2>/dev/null || { printf 'not-readable\\n' >&2; exit 5; }
			[ -r . ] || { printf 'not-readable\\n' >&2; exit 5; }
			printf '%s\\0' "$(pwd -P)"
			n=0
			for e in * .*; do
			  if [ "$e" = "." ] || [ "$e" = ".." ]; then continue; fi
			  if [ ! -e "$e" ] && [ ! -L "$e" ]; then continue; fi
			  if [ -L "$e" ]; then t=l
			  elif [ -d "$e" ]; then t=d
			  elif [ -f "$e" ]; then t=f
			  else t=o
			  fi
			  m=$(stat -c '%s|%a|%Y|%U|%G' "$e" 2>/dev/null || echo '')
			  lt=''
			  ld=''
			  if [ "$t" = l ]; then
			    lt=$(readlink "$e" 2>/dev/null || echo '')
			    if [ -d "$e" ]; then ld=d; elif [ -f "$e" ]; then ld=f; fi
			  fi
			  printf '%s\\0%s\\0%s\\0%s\\0%s\\0' "$e" "$t" "$m" "$lt" "$ld"
			  n=$((n+1))
			  if [ "$n" -ge "$2" ]; then printf '%s\\0' '__kwfb_truncated__'; break; fi
			done
			exit 0
			""";

	/**
	 * {@code $1} = path. Emits eight NUL-terminated fields: type, size, mode, mtime,
	 * owner, group, symlink target, resolved real path. Always exits 0 — "does not exist"
	 * is reported as type {@code x}, not as a failure, so the caller can tell a missing
	 * file from a broken container.
	 */
	static final String STAT = """
			p=$1
			if [ -L "$p" ]; then t=l
			elif [ -d "$p" ]; then t=d
			elif [ -f "$p" ]; then t=f
			elif [ -e "$p" ]; then t=o
			else t=x
			fi
			sz=$( { stat -c '%s' "$p" || wc -c < "$p"; } 2>/dev/null )
			md=$(stat -c '%a' "$p" 2>/dev/null || echo '')
			mt=$(stat -c '%Y' "$p" 2>/dev/null || echo '')
			ow=$(stat -c '%U' "$p" 2>/dev/null || echo '')
			gr=$(stat -c '%G' "$p" 2>/dev/null || echo '')
			lt=''
			if [ "$t" = l ]; then lt=$(readlink "$p" 2>/dev/null || echo ''); fi
			rp=$(readlink -f "$p" 2>/dev/null || echo '')
			printf '%s\\0%s\\0%s\\0%s\\0%s\\0%s\\0%s\\0%s\\0' "$t" "$sz" "$md" "$mt" "$ow" "$gr" "$lt" "$rp"
			exit 0
			""";

	/**
	 * {@code $1} = path, {@code $2} = byte cap. Writes the file's bytes to stdout
	 * verbatim — no TTY is attached, so nothing translates them (see
	 * {@code ExecService.run}).
	 */
	static final String READ = """
			p=$1
			if [ ! -e "$p" ]; then printf 'missing\\n' >&2; exit 3; fi
			if [ -d "$p" ]; then printf 'is-a-directory\\n' >&2; exit 4; fi
			if [ ! -f "$p" ]; then printf 'not-a-regular-file\\n' >&2; exit 5; fi
			if [ ! -r "$p" ]; then printf 'not-readable\\n' >&2; exit 6; fi
			if command -v head >/dev/null 2>&1; then head -c "$2" < "$p"; else cat < "$p"; fi
			""";

	/**
	 * {@code $1} = path, {@code $2} = expected byte count on stdin.
	 *
	 * <p>
	 * The payload lands in a sibling temp file first and its length is verified
	 * <em>before</em> the target is touched, so a connection that drops mid-upload leaves
	 * the original file intact instead of truncating it. If the size cannot be measured
	 * ({@code wc} missing) the write is refused — corrupting somebody's config file is a
	 * worse outcome than a clear failure. The final copy is a redirect rather than a
	 * {@code mv} so the target keeps its inode, owner and mode.
	 *
	 * <p>
	 * <strong>It reads exactly {@code $2} bytes rather than to end-of-input, and that is
	 * load-bearing.</strong> The Kubernetes exec protocol gives the client no way to say
	 * "stdin is finished": a plain {@code cat} therefore blocks until the whole session
	 * is torn down. Observed against a real cluster — every write sat for the full
	 * {@code command-timeout} and came back {@code 504 container-command-timeout}, and
	 * then landed anyway once the connection dropped, so the API reported failure for a
	 * write that had happened. {@code head -c} returns as soon as it has the bytes it was
	 * promised, which ends the command and lets the exit status come back. The
	 * {@code cat} fallback is kept for an image without {@code head}: it still writes the
	 * right bytes, it just cannot finish early.
	 */
	static final String WRITE = """
			p=$1
			if [ -d "$p" ]; then printf 'is-a-directory\\n' >&2; exit 4; fi
			t="$p.kweblens-upload.$$"
			if command -v head >/dev/null 2>&1; then
			  head -c "$2" > "$t" || { rm -f "$t" 2>/dev/null; printf 'cannot-write\\n' >&2; exit 5; }
			else
			  cat > "$t" || { rm -f "$t" 2>/dev/null; printf 'cannot-write\\n' >&2; exit 5; }
			fi
			n=$(wc -c < "$t" 2>/dev/null || echo x)
			n=$(printf '%s' "$n" | tr -dc '0-9')
			if [ "$n" != "$2" ]; then
			  rm -f "$t" 2>/dev/null
			  printf 'size-mismatch\\n' >&2
			  exit 6
			fi
			cat "$t" > "$p" || { rm -f "$t" 2>/dev/null; printf 'cannot-replace\\n' >&2; exit 7; }
			rm -f "$t" 2>/dev/null
			exit 0
			""";

	/**
	 * {@code $1} = path. Regular files and symlinks only — directories are refused
	 * outright rather than offering a recursive delete, because {@code rm -rf} driven
	 * from a browser is not a mistake anyone recovers from.
	 */
	static final String DELETE = """
			p=$1
			if [ ! -e "$p" ] && [ ! -L "$p" ]; then printf 'missing\\n' >&2; exit 3; fi
			if [ -d "$p" ] && [ ! -L "$p" ]; then printf 'is-a-directory\\n' >&2; exit 4; fi
			rm -f "$p" || { printf 'cannot-delete\\n' >&2; exit 5; }
			exit 0
			""";

	private PodFileScripts() {
	}

	/** Build the argv for {@code sh -c <script> kweblens-files <args...>}. */
	static List<String> command(String script, String... args) {
		List<String> argv = new ArrayList<>(4 + args.length);
		argv.add("sh");
		argv.add("-c");
		argv.add(script);
		argv.add(ARGV0);
		argv.addAll(List.of(args));
		return List.copyOf(argv);
	}

}
