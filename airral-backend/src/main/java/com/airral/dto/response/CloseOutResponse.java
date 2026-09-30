package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloseOutResponse {
    private boolean markedFilled;
    private int turnedDown;
    /** Candidates left as they were because an offer is out to them. */
    private int withOpenOffers;
}
