package com.airral.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Wrapping up a job once someone is hired. Each step is optional. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloseOutRequest {

    /** Mark the job filled, so it stops taking applications. */
    private Boolean markFilled;

    /** Turn down the candidates still in progress. Anyone with an offer out is left alone. */
    private Boolean turnDownOthers;

    /** Email the candidates turned down that the company is not moving forward. */
    private Boolean notifyCandidates;
}
