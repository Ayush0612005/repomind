package org.acme.library.member;

import java.util.Optional;
import java.util.UUID;

public interface MemberRepository {

	Optional<Member> findById(UUID id);

	boolean existsByEmail(String email);

	Member save(Member member);

}
