package org.alexmond.kweblens.web.api;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.web.helm.ValuesLibraryService;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * The reusable Helm values-file library — cluster-agnostic named YAML files the user
 * saves and reuses across installs/upgrades. Reads are open (in open-mode); saves and
 * deletes are non-GET, so {@code SecurityConfig} gates them behind the admin login.
 */
@RestController
@RequestMapping("/api/v1/helm/values")
@RequiredArgsConstructor
public class HelmValuesApiController {

	private final ValuesLibraryService library;

	private final AuditService audit;

	@GetMapping
	public List<String> list() {
		return library.list();
	}

	@GetMapping(value = "/{name}", produces = "application/yaml")
	public ResponseEntity<String> get(@PathVariable String name) {
		String yaml = library.get(name);
		return (yaml != null) ? ResponseEntity.ok(yaml) : ResponseEntity.notFound().build();
	}

	@PutMapping(value = "/{name}", consumes = { "application/yaml", "text/plain" })
	public void save(@PathVariable String name, @RequestBody(required = false) String yaml) {
		library.save(name, yaml);
		audit.record("-", "helm-values-save", name);
	}

	@DeleteMapping("/{name}")
	public ResponseEntity<Void> delete(@PathVariable String name) {
		library.delete(name);
		audit.record("-", "helm-values-delete", name);
		return ResponseEntity.noContent().build();
	}

}
