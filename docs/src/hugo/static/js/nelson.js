$(':header').each(function(){
  var item = $(this);
  var h3id = item.attr('id');
  if (h3id != undefined){
    var elem = '<a href="#'+h3id+'" aria-hidden="true"><i class="fas fa-anchor" aria-hidden="true"></i></a>'
    item.append(elem);
  }
});

