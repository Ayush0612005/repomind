package org.acme.petclinic.owner;

import java.util.List;
import java.util.Optional;

/**
 * Application service for pet owners.
 */
public class OwnerService {

	private final OwnerRepository repository;

	private int lookups;

	public OwnerService(OwnerRepository repository) {
		this.repository = repository;
	}

	/**
	 * Finds an owner by id, counting the lookup.
	 */
	public Optional<Owner> findOwner(int id) {
		lookups++;
		return repository.findById(id);
	}

	public List<Owner> findByLastName(String lastName) {
		if (lastName == null || lastName.isBlank()) {
			return repository.findAll();
		}
		return repository.findByLastNameStartingWith(lastName);
	}

	public int getLookups() {
		return lookups;
	}

	public void setLookups(int lookups) {
		this.lookups = lookups;
	}

	/** Validation outcome for an owner form. */
	public record Validation(boolean valid, List<String> errors) {

		public Validation {
			errors = List.copyOf(errors);
		}

	}

	interface OwnerRepository {

		Optional<Owner> findById(int id);

		List<Owner> findAll();

		List<Owner> findByLastNameStartingWith(String prefix);

	}

	enum Status {
		ACTIVE, ARCHIVED
	}

}
