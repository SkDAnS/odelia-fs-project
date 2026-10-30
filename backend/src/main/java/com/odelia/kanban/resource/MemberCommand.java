package com.odelia.kanban.resource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemberCommand(@NotBlank @Size(max = 120) String username) {
}
