package com.airral.service;

import com.airral.repository.OfferRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** An offers table with nothing open in it, for tests about something else. */
final class NoOffers {

    private NoOffers() {
    }

    static OfferRepository repository() {
        OfferRepository offers = mock(OfferRepository.class);
        when(offers.existsOpenByApplicationId(any(), any())).thenReturn(Mono.just(false));
        when(offers.existsAwaitingAnswer(any(), any())).thenReturn(Mono.just(false));
        when(offers.expireLapsed(any(), any())).thenReturn(Mono.just(0L));
        when(offers.closeOpen(any(), any())).thenReturn(Mono.just(0L));
        when(offers.closeSent(any(), any())).thenReturn(Mono.just(0L));
        when(offers.findApplicationIdsWithOpenOffers(any(), any())).thenReturn(Flux.empty());
        return offers;
    }
}
