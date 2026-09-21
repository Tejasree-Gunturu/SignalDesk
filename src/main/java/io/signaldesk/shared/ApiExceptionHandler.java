package io.signaldesk.shared;
import io.signaldesk.incident.application.IncidentNotFoundException;
import io.signaldesk.incident.domain.InvalidTransitionException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.time.*;
import java.util.*;
@RestControllerAdvice
public class ApiExceptionHandler {
    private final Clock clock; public ApiExceptionHandler(Clock clock){this.clock=clock;}
    @ExceptionHandler(IncidentNotFoundException.class) ResponseEntity<ApiError> notFound(IncidentNotFoundException e){return error(HttpStatus.NOT_FOUND,e.getMessage(),Map.of());}
    @ExceptionHandler(InvalidTransitionException.class) ResponseEntity<ApiError> invalidTransition(InvalidTransitionException e){return error(HttpStatus.CONFLICT,e.getMessage(),Map.of());}
    @ExceptionHandler({OptimisticLockException.class,OptimisticLockingFailureException.class}) ResponseEntity<ApiError> concurrentUpdate(RuntimeException e){return error(HttpStatus.CONFLICT,"The incident changed concurrently. Reload it and retry the operation.",Map.of());}
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<ApiError> validation(MethodArgumentNotValidException e){Map<String,String> fields=new LinkedHashMap<>();e.getBindingResult().getFieldErrors().forEach(x->fields.put(x.getField(),x.getDefaultMessage()));return error(HttpStatus.BAD_REQUEST,"Request validation failed",fields);}
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentTypeMismatchException.class}) ResponseEntity<ApiError> badRequest(Exception e){return error(HttpStatus.BAD_REQUEST,e.getMessage(),Map.of());}
    private ResponseEntity<ApiError> error(HttpStatus status,String message,Map<String,String> fieldErrors){return ResponseEntity.status(status).body(new ApiError(Instant.now(clock),status.value(),status.getReasonPhrase(),message,fieldErrors));}
}
