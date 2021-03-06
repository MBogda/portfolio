/*
 * Portfolio
 * Copyright (C) 2020  Vitalii Ananev <an-vitek@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.portfolio.portfolio.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.portfolio.portfolio.converter.SecurityConverter;
import ru.portfolio.portfolio.entity.SecurityEntity;
import ru.portfolio.portfolio.pojo.Security;
import ru.portfolio.portfolio.repository.SecurityRepository;

import javax.validation.Valid;
import java.util.List;
import java.util.Optional;

@RestController
public class SecurityRestController extends AbstractRestController<String, Security, SecurityEntity> {
    private final SecurityRepository repository;

    public SecurityRestController(SecurityRepository repository, SecurityConverter converter) {
        super(repository, converter);
        this.repository = repository;
    }

    @GetMapping("/securities")
    @Override
    public List<SecurityEntity> get() {
        return super.get();
    }

    @GetMapping("/securities/{isin}")
    @Override
    public ResponseEntity<SecurityEntity> get(@PathVariable("isin") String isin) {
        return super.get(isin);
    }

    @PostMapping("/securities")
    @Override
    public ResponseEntity<SecurityEntity> post(@Valid @RequestBody Security security) {
        return super.post(security);
    }

    @PutMapping("/securities/{isin}")
    @Override
    public ResponseEntity<SecurityEntity> put(@PathVariable("isin") String isin, @Valid @RequestBody Security security) {
        return super.put(isin, security);
    }

    @DeleteMapping("/securities/{isin}")
    @Override
    public void delete(@PathVariable("isin") String isin) {
        super.delete(isin);
    }

    @Override
    protected Optional<SecurityEntity> getById(String isin) {
        return repository.findByIsin(isin);
    }

    @Override
    protected String getId(Security object) {
        return object.getIsin();
    }

    @Override
    protected Security updateId(String isin, Security object) {
        return object.toBuilder().isin(isin).build();
    }

    @Override
    protected String getLocation() {
        return "/securities";
    }
}
